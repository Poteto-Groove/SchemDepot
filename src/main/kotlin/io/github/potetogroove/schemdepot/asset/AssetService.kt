package io.github.potetogroove.schemdepot.asset

import com.sk89q.worldedit.extent.clipboard.Clipboard
import io.github.potetogroove.schemdepot.config.SchemDepotConfig
import io.github.potetogroove.schemdepot.permission.Permissions
import io.github.potetogroove.schemdepot.storage.SchematicStorage
import io.github.potetogroove.schemdepot.worldedit.ClipboardService
import io.github.potetogroove.schemdepot.worldedit.PasteService
import org.bukkit.entity.Player
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Application/use-case layer coordinating [AssetRepository] and [SchematicStorage] with the
 * WorldEdit integration ([ClipboardService], [PasteService]) to implement SchemDepot's six
 * operations (docs/SchemDepot_DESIGN.md SS15.2, SS13, SS29 Phase 5): [add], [paste], [list],
 * [info], [rename], [remove].
 *
 * ## Source of truth (SS19, SS36 invariant 4)
 * SQLite (via [repository]) is always the source of truth. [index] is a read-optimization only:
 * it is populated at startup by [loadIndexBlocking] and is only ever updated **after** a storage
 * mutation has already committed successfully - never before a mutation, and never on a failure
 * path.
 *
 * ## Threading (SS12, AC-12)
 * Every public method documents, in its own KDoc, which thread it must be called from and which
 * thread its `callback` runs on. As a summary:
 * - [add] and [paste] must be called from the Bukkit **main thread** - they read live
 *   `Player`/WorldEdit session state before doing anything else.
 * - [list], [info], [rename], and [remove] touch no Bukkit API at all and are safe to call from
 *   any thread.
 * - In every case, SQLite/filesystem work is dispatched onto [workerExecutor], and every
 *   `callback` is always invoked via [mainThreadDispatcher] so the Phase 6 command layer can
 *   safely send chat messages / touch other Bukkit state from it.
 *
 * ## Testability (SS27.1)
 * [workerExecutor] and [mainThreadDispatcher] are injected specifically so unit tests can run
 * everything synchronously (e.g. `Executor { it.run() }` and `{ it.run() }`). [rename], [remove],
 * [list], and [info] take no Bukkit type at all and are fully unit-testable this way. [add] and
 * [paste] still need a live `org.bukkit.entity.Player`/WorldEdit platform for their main-thread
 * prefix, so their worker-thread cores are additionally exposed as `internal` methods
 * ([performAdd], [resolveAndLoadClipboard]) that unit tests can call directly, bypassing the
 * Bukkit-dependent part. Full end-to-end coverage of [add]/[paste] is deferred to the Phase 7
 * integration pass (design doc SS29 Phase 7).
 */
class AssetService(
    private val repository: AssetRepository,
    private val schematicStorage: SchematicStorage,
    private val clipboardService: ClipboardService,
    private val pasteService: PasteService,
    private val config: SchemDepotConfig,
    private val workerExecutor: Executor,
    private val mainThreadDispatcher: (Runnable) -> Unit,
    private val logger: Logger = Logger.getLogger(AssetService::class.java.name),
) {

    /** Read-optimization index over the registry, keyed by normalized name (SS19). */
    private val index = ConcurrentHashMap<String, Asset>()

    /**
     * Rebuilds [index] from every row currently in [repository] (SS19 startup steps 1-4).
     *
     * **Worker thread only** - performs blocking SQLite and filesystem I/O. Call exactly once
     * during plugin startup, after the database has been migrated and before any command is
     * dispatched; not safe to call concurrently with itself. Logs a warning (does not fail) for
     * every asset whose backing schematic file is missing, per SS19/SS32.
     */
    fun loadIndexBlocking() {
        val assets = repository.list(Int.MAX_VALUE, 0)
        index.clear()
        for (asset in assets) {
            index[asset.normalizedName] = asset
            val resolution = schematicStorage.resolveForRead(asset.id)
            if (resolution !is SchematicStorage.ReadResolution.Found) {
                logger.warning(
                    "Asset ${asset.name} (${asset.id}) references a missing schematic file.",
                )
            }
        }
        logger.info("Loaded ${assets.size} asset(s) into the in-memory index.")
    }

    // -------------------------------------------------------------------------------------
    // add (SS13.1, AC-01/AC-02/AC-03)
    // -------------------------------------------------------------------------------------

    /**
     * Registers [player]'s current WorldEdit clipboard as a new asset named [rawName]
     * (SS13.1, AC-01/AC-02/AC-03).
     *
     * **Call from the Bukkit main thread.** This reads live Bukkit/WorldEdit state ([player]'s
     * permission, `LocalSession` clipboard, and identity) before any I/O happens (SS12/AC-12).
     * The schematic write and database insert are then dispatched to [workerExecutor]; [callback]
     * is always invoked back on the main thread via [mainThreadDispatcher].
     *
     * Never replaces, mutates, or closes [player]'s WorldEdit clipboard (SS10.1, SS30-3/4).
     */
    fun add(player: Player, rawName: String, callback: (AddResult) -> Unit) {
        if (!player.hasPermission(Permissions.ADD)) {
            callback(AddResult.NoPermission)
            return
        }

        val validName = when (val nameResult = AssetName.validate(rawName)) {
            is AssetNameResult.InvalidFormat -> {
                callback(AddResult.InvalidName(rawName))
                return
            }
            is AssetNameResult.Reserved -> {
                callback(AddResult.ReservedName(rawName))
                return
            }
            is AssetNameResult.Valid -> nameResult
        }

        val clipboard = when (val clipboardResult = clipboardService.currentClipboardOf(player)) {
            is ClipboardService.ClipboardResult.Empty -> {
                callback(AddResult.EmptyClipboard)
                return
            }
            is ClipboardService.ClipboardResult.Failed -> {
                callback(AddResult.InternalError(clipboardResult.cause))
                return
            }
            is ClipboardService.ClipboardResult.Available -> clipboardResult.clipboard
        }

        val dimensions = clipboardService.dimensionsOf(clipboard)
        val maxVolume = config.limits.maxVolume
        if (maxVolume > 0 && dimensions.volume > maxVolume) {
            callback(AddResult.VolumeLimitExceeded(dimensions.volume, maxVolume))
            return
        }

        // Fast index-based duplicate check (SS19/SS22); the authoritative check against the
        // database itself happens in performAdd, right before the write.
        if (index.containsKey(validName.normalizedName)) {
            callback(AddResult.DuplicateName(validName.name))
            return
        }

        val id = UUID.randomUUID()
        val authorUuid = player.uniqueId
        val authorName = player.name

        workerExecutor.execute {
            val result = try {
                performAdd(id, validName, authorUuid, authorName, dimensions) { output ->
                    clipboardService.writeTo(clipboard, output)
                }
            } catch (e: Exception) {
                logger.log(
                    Level.SEVERE,
                    "Unexpected failure while adding asset '${validName.name}'.",
                    e,
                )
                AddResult.InternalError(e)
            }

            mainThreadDispatcher(
                Runnable {
                    if (result is AddResult.Success) {
                        index[result.asset.normalizedName] = result.asset
                    }
                    callback(result)
                },
            )
        }
    }

    /**
     * Worker-thread core of [add]: authoritative duplicate check, schematic write, and database
     * insert (SS13.1 steps 7-12). Touches no Bukkit/WorldEdit API directly - [writeClipboard] is
     * the only place that reaches back into WorldEdit, already bound to a specific [Clipboard] by
     * the caller.
     *
     * If the database insert fails *after* the schematic file was written, the file is moved to
     * trash and permanently deleted so no orphan file remains (AC-03, SS13.1 tail, SS30-19): a
     * failed write/insert is never reported as [AddResult.Success].
     *
     * Exposed as `internal` so unit tests can exercise this write/insert/cleanup transaction
     * without a live Bukkit/WorldEdit platform (docs/SchemDepot_DESIGN.md SS27.1). Production
     * callers must go through [add].
     *
     * **Worker thread only.**
     */
    internal fun performAdd(
        id: UUID,
        validName: AssetNameResult.Valid,
        authorUuid: UUID,
        authorName: String,
        dimensions: ClipboardService.ClipboardDimensions,
        writeClipboard: (OutputStream) -> Unit,
    ): AddResult {
        if (repository.existsByName(validName.normalizedName)) {
            return AddResult.DuplicateName(validName.name)
        }

        val writtenPath = when (val writeResult = schematicStorage.write(id, writeClipboard)) {
            is SchematicStorage.WriteResult.AlreadyExists -> {
                logger.severe(
                    "Schematic file already existed for freshly generated asset id $id; " +
                        "refusing to add '${validName.name}'.",
                )
                return AddResult.InternalError(
                    IllegalStateException("Schematic file already exists for id $id"),
                )
            }
            is SchematicStorage.WriteResult.Failed -> {
                logger.log(
                    Level.WARNING,
                    "Failed to write schematic for asset '${validName.name}' ($id).",
                    writeResult.cause,
                )
                return AddResult.InternalError(writeResult.cause)
            }
            is SchematicStorage.WriteResult.Success -> writeResult.path
        }

        val now = Instant.now()
        val asset = Asset(
            id = id,
            name = validName.name,
            normalizedName = validName.normalizedName,
            authorUuid = authorUuid,
            authorName = authorName,
            createdAt = now,
            updatedAt = now,
            sizeX = dimensions.sizeX,
            sizeY = dimensions.sizeY,
            sizeZ = dimensions.sizeZ,
            schematicFile = writtenPath.fileName.toString(),
        )

        return try {
            repository.insert(asset)
            logger.info("Registered asset ${asset.name} ($id) by $authorUuid")
            AddResult.Success(asset)
        } catch (e: Exception) {
            logger.log(
                Level.SEVERE,
                "Failed to insert asset '${validName.name}' ($id) into the database after " +
                    "writing its schematic; removing the orphan file. A failed write must never " +
                    "be reported as success.",
                e,
            )
            cleanupOrphanSchematic(id, validName.name)
            AddResult.InternalError(e)
        }
    }

    /** Best-effort removal of an already-written schematic whose DB insert failed (AC-03). */
    private fun cleanupOrphanSchematic(id: UUID, assetName: String) {
        when (val trashResult = schematicStorage.moveToTrash(id)) {
            is SchematicStorage.TrashMoveResult.Moved -> {
                val deleteResult = schematicStorage.deleteFromTrash(id)
                if (deleteResult is SchematicStorage.TrashDeleteResult.Failed) {
                    logger.log(
                        Level.SEVERE,
                        "Failed to delete orphan schematic for '$assetName' ($id) from trash " +
                            "after a failed database insert. Manual cleanup required.",
                        deleteResult.cause,
                    )
                }
            }
            is SchematicStorage.TrashMoveResult.Failed -> {
                logger.log(
                    Level.SEVERE,
                    "Failed to move orphan schematic for '$assetName' ($id) to trash after a " +
                        "failed database insert. Manual cleanup required.",
                    trashResult.cause,
                )
            }
            SchematicStorage.TrashMoveResult.SourceMissing -> {
                logger.warning(
                    "Orphan schematic cleanup for '$assetName' ($id) found no file to remove.",
                )
            }
        }
    }

    // -------------------------------------------------------------------------------------
    // paste (SS13.2, AC-04/AC-05/AC-06/AC-11)
    // -------------------------------------------------------------------------------------

    /** Worker-thread-safe outcome of [resolveAndLoadClipboard]. No Bukkit API is touched here. */
    internal sealed interface ClipboardLoad {
        data class Loaded(val clipboard: Clipboard) : ClipboardLoad
        data object Unavailable : ClipboardLoad
    }

    /**
     * Pastes the asset named [rawName] at [player]'s current position (SS13.2, AC-04/AC-05).
     *
     * **Call from the Bukkit main thread.** [player]'s permission, current
     * [org.bukkit.Location], and [org.bukkit.World] are all live Bukkit state and must be read
     * there (SS12/AC-12). The schematic file is then resolved and loaded on [workerExecutor]; the
     * actual WorldEdit paste hops back to the main thread. [callback] is always invoked on the
     * main thread via [mainThreadDispatcher].
     *
     * Never touches [player]'s WorldEdit clipboard (SS10.5, SS30-3, invariant 3, AC-04).
     */
    fun paste(player: Player, rawName: String, callback: (PasteResult) -> Unit) {
        if (!player.hasPermission(Permissions.PASTE)) {
            callback(PasteResult.NoPermission)
            return
        }

        val asset = index[AssetName.normalize(rawName)]
        if (asset == null) {
            callback(PasteResult.NotFound(rawName))
            return
        }

        // Snapshot main-thread-only state before hopping to the worker executor (SS12).
        val location = player.location
        val world = player.world

        workerExecutor.execute {
            val loaded = resolveAndLoadClipboard(asset)

            mainThreadDispatcher(
                Runnable {
                    when (loaded) {
                        is ClipboardLoad.Loaded -> {
                            val target = pasteService.pasteTargetOf(location)
                            val pasteOutcome = pasteService.pasteForPlayer(
                                player,
                                loaded.clipboard,
                                world,
                                target,
                                config.paste,
                            )
                            callback(
                                when (pasteOutcome) {
                                    is PasteService.PasteResult.Success -> PasteResult.Success(asset)
                                    is PasteService.PasteResult.Failed ->
                                        PasteResult.InternalError(pasteOutcome.cause)
                                },
                            )
                        }
                        ClipboardLoad.Unavailable -> callback(PasteResult.AssetFileUnavailable(asset))
                    }
                },
            )
        }
    }

    /**
     * Resolves [asset]'s backing schematic file and loads it into a plugin-owned clipboard
     * (SS13.2). `NotFound`/`Rejected`/`FileMissing`/`UnknownFormat`/read failures all collapse
     * into [ClipboardLoad.Unavailable] (AC-11: "The asset file is missing or corrupted.").
     *
     * Exposed as `internal` so unit tests can exercise the missing-file path without a live
     * Bukkit/WorldEdit platform. Production callers must go through [paste].
     *
     * **Worker thread only.**
     */
    internal fun resolveAndLoadClipboard(asset: Asset): ClipboardLoad {
        val path = when (val resolution = schematicStorage.resolveForRead(asset.id)) {
            is SchematicStorage.ReadResolution.Found -> resolution.path
            SchematicStorage.ReadResolution.NotFound -> {
                logger.warning(
                    "Asset ${asset.name} (${asset.id}) references a missing schematic file.",
                )
                return ClipboardLoad.Unavailable
            }
            SchematicStorage.ReadResolution.Rejected -> {
                logger.severe(
                    "Asset ${asset.name} (${asset.id}) resolved to a schematic path outside " +
                        "the schematics directory; refusing to load it.",
                )
                return ClipboardLoad.Unavailable
            }
        }

        return when (val loadResult = pasteService.loadClipboard(path)) {
            is PasteService.LoadResult.Success -> ClipboardLoad.Loaded(loadResult.clipboard)
            PasteService.LoadResult.FileMissing,
            PasteService.LoadResult.UnknownFormat,
            is PasteService.LoadResult.Failed,
            -> ClipboardLoad.Unavailable
        }
    }

    // -------------------------------------------------------------------------------------
    // list (SS5.3, SS27.1 pagination)
    // -------------------------------------------------------------------------------------

    /**
     * Returns page [page] (1-based) of registered assets, ordered by registration time (SS5.3).
     *
     * Safe to call from any thread; touches no Bukkit API. The database read runs on
     * [workerExecutor]; [callback] is always invoked via [mainThreadDispatcher]. Out-of-range
     * [page] values are clamped into `[1, totalPages]` rather than treated as an error.
     */
    fun list(page: Int, hasPermission: (String) -> Boolean, callback: (ListResult) -> Unit) {
        if (!hasPermission(Permissions.LIST)) {
            callback(ListResult.NoPermission)
            return
        }

        workerExecutor.execute {
            val result = try {
                val pageSize = config.list.pageSize
                val totalCount = repository.count()
                val totalPages = if (totalCount <= 0L) {
                    1
                } else {
                    ((totalCount + pageSize - 1) / pageSize).toInt()
                }
                val clampedPage = page.coerceIn(1, totalPages)
                val offset = (clampedPage - 1) * pageSize
                val assets = repository.list(pageSize, offset)
                ListResult.Success(assets, clampedPage, pageSize, totalCount, totalPages)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to list assets (requested page $page).", e)
                ListResult.InternalError(e)
            }
            mainThreadDispatcher(Runnable { callback(result) })
        }
    }

    // -------------------------------------------------------------------------------------
    // info (SS5.4)
    // -------------------------------------------------------------------------------------

    /**
     * Looks up a single asset by name (SS5.4).
     *
     * Safe to call from any thread; touches no Bukkit API. The database read runs on
     * [workerExecutor]; [callback] is always invoked via [mainThreadDispatcher].
     */
    fun info(rawName: String, hasPermission: (String) -> Boolean, callback: (InfoResult) -> Unit) {
        if (!hasPermission(Permissions.INFO)) {
            callback(InfoResult.NoPermission)
            return
        }

        val normalizedName = AssetName.normalize(rawName)
        workerExecutor.execute {
            val result = try {
                val asset = repository.findByName(normalizedName)
                if (asset != null) InfoResult.Success(asset) else InfoResult.NotFound(rawName)
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "Failed to load asset info for '$rawName'.", e)
                InfoResult.InternalError(e)
            }
            mainThreadDispatcher(Runnable { callback(result) })
        }
    }

    // -------------------------------------------------------------------------------------
    // rename (SS13.3, AC-09, AC-10)
    // -------------------------------------------------------------------------------------

    /**
     * Renames the asset called [oldRawName] to [newRawName], updating registry metadata only
     * (SS13.3, AC-09). `schematic_file` and the on-disk filename are never changed by a rename.
     *
     * Safe to call from any thread; touches no Bukkit API. The database update runs on
     * [workerExecutor]; [callback] is always invoked via [mainThreadDispatcher].
     *
     * @param callerUuid the invoking sender's UUID. Ownership is decided by comparing this to
     *   [Asset.authorUuid] - never by display name (SS21-4/21-11, AC-10).
     * @param hasPermission permission lookup for the invoking sender (e.g. `player::hasPermission`
     *   from the Phase 6 command layer). [Permissions.RENAME_OWN] is required when [callerUuid]
     *   owns the asset, [Permissions.RENAME_ANY] otherwise.
     */
    fun rename(
        callerUuid: UUID,
        oldRawName: String,
        newRawName: String,
        hasPermission: (String) -> Boolean,
        callback: (RenameResult) -> Unit,
    ) {
        val asset = index[AssetName.normalize(oldRawName)]
        if (asset == null) {
            callback(RenameResult.NotFound(oldRawName))
            return
        }

        if (!canModify(asset, callerUuid, hasPermission, Permissions.RENAME_OWN, Permissions.RENAME_ANY)) {
            callback(RenameResult.NoPermission)
            return
        }

        val newName = when (val nameResult = AssetName.validate(newRawName)) {
            is AssetNameResult.InvalidFormat -> {
                callback(RenameResult.InvalidName(newRawName))
                return
            }
            is AssetNameResult.Reserved -> {
                callback(RenameResult.ReservedName(newRawName))
                return
            }
            is AssetNameResult.Valid -> nameResult
        }

        if (newName.normalizedName != asset.normalizedName && index.containsKey(newName.normalizedName)) {
            callback(RenameResult.DuplicateName(newName.name))
            return
        }

        workerExecutor.execute {
            val result = performRename(asset, newName, callerUuid)
            mainThreadDispatcher(
                Runnable {
                    if (result is RenameResult.Success) {
                        index.remove(asset.normalizedName)
                        index[result.asset.normalizedName] = result.asset
                    }
                    callback(result)
                },
            )
        }
    }

    /**
     * Worker-thread core of [rename]: authoritative duplicate check and the metadata-only SQLite
     * update (SS13.3). Touches no Bukkit API; exposed as `internal` purely so unit tests can
     * exercise it directly (SS27.1). Production callers must go through [rename].
     *
     * **Worker thread only.**
     */
    internal fun performRename(
        asset: Asset,
        newName: AssetNameResult.Valid,
        callerUuid: UUID,
    ): RenameResult {
        if (newName.normalizedName != asset.normalizedName &&
            repository.existsByName(newName.normalizedName)
        ) {
            return RenameResult.DuplicateName(newName.name)
        }

        val updatedAt = Instant.now()
        return try {
            repository.updateName(asset.id, newName.name, newName.normalizedName, updatedAt)
            logger.info("Renamed asset ${asset.name} -> ${newName.name} (${asset.id}) by $callerUuid")
            RenameResult.Success(
                asset.copy(
                    name = newName.name,
                    normalizedName = newName.normalizedName,
                    updatedAt = updatedAt,
                ),
            )
        } catch (e: Exception) {
            logger.log(
                Level.SEVERE,
                "Failed to rename asset ${asset.name} (${asset.id}) to ${newName.name}.",
                e,
            )
            RenameResult.InternalError(e)
        }
    }

    // -------------------------------------------------------------------------------------
    // remove (SS13.4, AC-10)
    // -------------------------------------------------------------------------------------

    /**
     * Removes the asset called [rawName]: moves its backing schematic to trash, deletes the
     * registry row, then permanently deletes the trashed file (SS13.4, AC-10).
     *
     * Safe to call from any thread; touches no Bukkit API. Storage/database work runs on
     * [workerExecutor]; [callback] is always invoked via [mainThreadDispatcher].
     *
     * @param callerUuid the invoking sender's UUID; ownership is decided by comparing this to
     *   [Asset.authorUuid] (SS21-4/21-11, AC-10).
     * @param hasPermission permission lookup for the invoking sender. [Permissions.REMOVE_OWN] is
     *   required when [callerUuid] owns the asset, [Permissions.REMOVE_ANY] otherwise.
     */
    fun remove(
        callerUuid: UUID,
        rawName: String,
        hasPermission: (String) -> Boolean,
        callback: (RemoveResult) -> Unit,
    ) {
        val asset = index[AssetName.normalize(rawName)]
        if (asset == null) {
            callback(RemoveResult.NotFound(rawName))
            return
        }

        if (!canModify(asset, callerUuid, hasPermission, Permissions.REMOVE_OWN, Permissions.REMOVE_ANY)) {
            callback(RemoveResult.NoPermission)
            return
        }

        workerExecutor.execute {
            val result = performRemove(asset, callerUuid)
            mainThreadDispatcher(
                Runnable {
                    if (result is RemoveResult.Success) {
                        index.remove(asset.normalizedName)
                    }
                    callback(result)
                },
            )
        }
    }

    /**
     * Worker-thread core of [remove]: trash-move, registry delete, then permanent trash cleanup
     * (SS13.4). Touches no Bukkit API; exposed as `internal` purely so unit tests can exercise it
     * directly (SS27.1). Production callers must go through [remove].
     *
     * Known limitation: if the registry delete fails *after* the file was already moved to trash,
     * this logs a SEVERE message with enough detail (asset id, trash path) for manual recovery
     * rather than automatically moving the file back - [SchematicStorage] does not expose a
     * trash-to-schematics restore primitive, and this class must not be modified to add one under
     * the Phase 5 task scope. See the Phase 5 report for details.
     *
     * **Worker thread only.**
     */
    internal fun performRemove(asset: Asset, callerUuid: UUID): RemoveResult {
        val trashResult = schematicStorage.moveToTrash(asset.id)
        when (trashResult) {
            is SchematicStorage.TrashMoveResult.Failed ->
                logger.log(
                    Level.WARNING,
                    "Failed to move schematic for asset ${asset.name} (${asset.id}) to trash " +
                        "before removal; continuing with registry deletion anyway.",
                    trashResult.cause,
                )
            SchematicStorage.TrashMoveResult.SourceMissing ->
                logger.warning(
                    "Schematic file for asset ${asset.name} (${asset.id}) was already missing " +
                        "before removal.",
                )
            is SchematicStorage.TrashMoveResult.Moved -> {}
        }

        return try {
            repository.delete(asset.id)
            logger.info("Removed asset ${asset.name} (${asset.id}) by $callerUuid")

            if (trashResult is SchematicStorage.TrashMoveResult.Moved) {
                val deleteResult = schematicStorage.deleteFromTrash(asset.id)
                if (deleteResult is SchematicStorage.TrashDeleteResult.Failed) {
                    logger.log(
                        Level.WARNING,
                        "Failed to permanently delete trashed schematic for removed asset " +
                            "${asset.name} (${asset.id}); a leftover trash file remains at " +
                            "${trashResult.trashPath}.",
                        deleteResult.cause,
                    )
                }
            }
            RemoveResult.Success(asset)
        } catch (e: Exception) {
            logger.log(
                Level.SEVERE,
                "Failed to delete asset ${asset.name} (${asset.id}) from the database after " +
                    "moving its schematic to trash (trashResult=$trashResult). The registry row " +
                    "and the trashed file are now out of sync; manual recovery is required.",
                e,
            )
            RemoveResult.InternalError(e)
        }
    }

    // -------------------------------------------------------------------------------------
    // shared ownership policy (SS21-4/21-11, AC-10)
    // -------------------------------------------------------------------------------------

    /**
     * `.own`/`.any` ownership policy shared by [rename] and [remove] (AC-10). Ownership is
     * decided strictly by comparing [callerUuid] against [Asset.authorUuid] - never by display
     * name (SS21-4/SS21-11).
     */
    private fun canModify(
        asset: Asset,
        callerUuid: UUID,
        hasPermission: (String) -> Boolean,
        ownNode: String,
        anyNode: String,
    ): Boolean {
        return if (asset.authorUuid == callerUuid) {
            hasPermission(ownNode) || hasPermission(anyNode)
        } else {
            hasPermission(anyNode)
        }
    }
}
