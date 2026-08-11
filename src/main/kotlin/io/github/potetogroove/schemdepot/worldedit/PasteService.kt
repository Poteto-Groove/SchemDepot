package io.github.potetogroove.schemdepot.worldedit

import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.session.ClipboardHolder
import io.github.potetogroove.schemdepot.config.SchemDepotConfig
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Write side of the WorldEdit integration: loads a stored `.schem` into a plugin-owned clipboard
 * and pastes it through an [com.sk89q.worldedit.EditSession] (design doc SS10.3, SS10.4, SS10.5,
 * SS13.2 paste sequence).
 *
 * ## The player's clipboard is never touched (SS10.5, SS30-3, invariant 3, AC-04)
 * `/sd <name>` pastes from a clipboard this class loaded itself. SchemDepot never loads an asset
 * into the player's `LocalSession` clipboard and never executes `//paste` on their behalf, so a
 * builder's own `//copy` survives any number of SchemDepot pastes. The only `LocalSession` method
 * used during a paste is [com.sk89q.worldedit.LocalSession.remember].
 *
 * ## Threading split (SS12, AC-12)
 * Loading and pasting are deliberately **two separate methods** so the caller (the Phase 5
 * application service) can cross threads between them:
 *
 * 1. [loadClipboard] - blocking file I/O, no Bukkit API: run it on the plugin worker executor.
 * 2. [pasteForPlayer] - Bukkit/WorldEdit world mutation: run it on the **main server thread**.
 *
 * ## Testing
 * No unit tests exist for this class. Both methods need the WorldEdit platform to be initialized
 * (block/biome registries for reading, a live world and session for pasting), which is impossible
 * outside a running Paper + FAWE server. Verified in the Phase 7 integration pass, specifically
 * the `//copy` -> `/sd add` -> `/sd <name>` -> `//undo` sequence of design doc SS29/SS37
 * (AC-04, AC-05, AC-06, AC-11).
 */
class PasteService(
    private val facade: WorldEditFacade,
    private val logger: Logger = Logger.getLogger(PasteService::class.java.name),
) {

    /** Outcome of loading a stored schematic file into a plugin-owned clipboard. */
    sealed interface LoadResult {

        /**
         * The schematic was read successfully. This clipboard is **owned by SchemDepot** (it did
         * not come from a `LocalSession`), so it may be handed to [pasteForPlayer] freely.
         */
        data class Success(val clipboard: Clipboard) : LoadResult

        /** The file does not exist (AC-11: the backing schematic was removed behind our back). */
        data object FileMissing : LoadResult

        /** WorldEdit could not recognise the file as any known clipboard format (SS20.3). */
        data object UnknownFormat : LoadResult

        /**
         * The file exists and its format was recognised, but reading it failed (corrupt/truncated
         * data, I/O error). Already logged with the file path; [cause] must not be shown verbatim
         * to players (SS21-8, SS20.3).
         */
        data class Failed(val cause: Exception) : LoadResult
    }

    /** Outcome of a paste. */
    sealed interface PasteResult {
        /** The paste completed and was registered in the player's WorldEdit undo history. */
        data object Success : PasteResult

        /**
         * The paste failed. Already logged with context; [cause] must not be shown verbatim to
         * players (SS21-8). Any blocks that were changed before the failure remain undoable,
         * because the edit is registered in WorldEdit history regardless of the outcome.
         */
        data class Failed(val cause: Exception) : PasteResult
    }

    /**
     * Loads the schematic at [path] into a new, plugin-owned [Clipboard] (SS10.3).
     *
     * The format is auto-detected with `ClipboardFormats.findByPath`, so schematics written by
     * other tools (MCEdit, Sponge v1/v2/v3) still load. The
     * [com.sk89q.worldedit.extent.clipboard.io.ClipboardReader] is always closed via `use`.
     *
     * **Worker thread** - blocking file I/O, no Bukkit API is touched here (SS12, AC-12).
     * Never throws: every failure mode is reported as a [LoadResult] so the command layer can fail
     * gracefully (SS30-20, SS31).
     */
    fun loadClipboard(path: Path): LoadResult {
        if (!Files.isRegularFile(path)) {
            logger.warning("Schematic file is missing or not a regular file: $path")
            return LoadResult.FileMissing
        }

        // Verified: ClipboardFormats.findByPath(java.nio.file.Path): ClipboardFormat (nullable).
        val format = ClipboardFormats.findByPath(path)
        if (format == null) {
            logger.warning("Unrecognised schematic format, refusing to paste: $path")
            return LoadResult.UnknownFormat
        }

        return try {
            Files.newInputStream(path).use { rawInput ->
                readClipboard(format.getReader(BufferedInputStream(rawInput)))
            }
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Failed to read schematic file: $path", e)
            LoadResult.Failed(e)
        } catch (e: RuntimeException) {
            // A corrupt NBT payload can surface as an unchecked exception from the reader.
            logger.log(Level.WARNING, "Corrupt or unreadable schematic file: $path", e)
            LoadResult.Failed(e)
        }
    }

    private fun readClipboard(
        reader: com.sk89q.worldedit.extent.clipboard.io.ClipboardReader,
    ): LoadResult = reader.use { LoadResult.Success(it.read()) }

    /**
     * Stream-accepting variant of [loadClipboard] for callers that already hold an open stream.
     *
     * Format detection is not possible without a path or a re-readable source, so the caller must
     * name the [format] explicitly. The reader (and therefore [input]) is closed via `use`.
     *
     * **Worker thread**, same reasoning as [loadClipboard].
     */
    fun loadClipboard(
        input: InputStream,
        format: com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat,
    ): LoadResult {
        return try {
            readClipboard(format.getReader(BufferedInputStream(input)))
        } catch (e: IOException) {
            logger.log(Level.WARNING, "Failed to read schematic from stream.", e)
            LoadResult.Failed(e)
        } catch (e: RuntimeException) {
            logger.log(Level.WARNING, "Corrupt or unreadable schematic stream.", e)
            LoadResult.Failed(e)
        }
    }

    /**
     * Converts a Bukkit [location] into the [BlockVector3] paste target WorldEdit expects.
     *
     * Snapshot the player's [Location] on the main thread; the conversion itself is pure math and
     * may be done anywhere (SS12).
     */
    fun pasteTargetOf(location: Location): BlockVector3 = facade.toBlockVector3(location)

    /**
     * Pastes [clipboard] into [targetWorld] at [target] on behalf of [player] (SS10.4).
     *
     * **MAIN SERVER THREAD ONLY.** This mutates world state through Bukkit/WorldEdit and reads the
     * player's live `LocalSession`; calling it from the plugin worker executor violates SS12 and
     * AC-12. Load the clipboard on a worker thread with [loadClipboard] first, then hop back to
     * the main thread for this call.
     *
     * Behaviour:
     * - Paste options come from `config.yml`'s `paste:` block ([SchemDepotConfig.PasteSettings],
     *   SS11/SS16). Verified builder method names on WorldEdit 7.4.5: `ignoreAirBlocks(boolean)`,
     *   `copyEntities(boolean)`, `copyBiomes(boolean)`, `to(BlockVector3)`, `build()`.
     * - WorldEdit's clipboard origin/offset semantics are preserved because the paste is built
     *   through [ClipboardHolder.createPaste]; no block is ever iterated by SchemDepot (SS30-1,
     *   SS30-2).
     * - The edit is registered in the player's WorldEdit undo history so `//undo` reverts it
     *   (SS10.4, SS30-13, AC-06). `remember` is invoked in a `finally` block, *before* the
     *   `EditSession` is closed - this matches the order WorldEdit itself uses in
     *   `PlatformCommandManager` (verified by disassembly: `LocalSession.remember` then
     *   `EditSession.close`), and the `finally` guarantees that a partially applied, failed paste
     *   is still undoable. `LocalSession.remember` is a no-op when the edit changed nothing.
     * - The [com.sk89q.worldedit.EditSession] is always closed via `use`
     *   (`EditSession implements AutoCloseable`, verified).
     * - The [clipboard] is not closed here; ownership stays with the caller.
     *
     * Never throws: failures are returned as [PasteResult.Failed] after being logged (SS30-20).
     */
    fun pasteForPlayer(
        player: Player,
        clipboard: Clipboard,
        targetWorld: World,
        target: BlockVector3,
        pasteSettings: SchemDepotConfig.PasteSettings,
    ): PasteResult {
        return try {
            val actor = facade.actorOf(player)
            val localSession = facade.localSessionOf(actor)
            val weWorld = facade.adaptWorld(targetWorld)

            facade.newEditSession(weWorld, actor).use { editSession ->
                try {
                    val operation = ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(target)
                        .ignoreAirBlocks(pasteSettings.ignoreAirByDefault)
                        .copyEntities(pasteSettings.includeEntities)
                        .copyBiomes(pasteSettings.includeBiomes)
                        .build()

                    Operations.complete(operation)
                } finally {
                    // Register in WorldEdit history before closing, mirroring WorldEdit's own
                    // command pipeline. Kept in `finally` so an aborted paste stays undoable.
                    localSession.remember(editSession)
                }
            }

            PasteResult.Success
        } catch (e: WorldEditException) {
            logger.log(
                Level.WARNING,
                "WorldEdit failed to paste an asset for player ${player.uniqueId} " +
                    "at ${target.x()}/${target.y()}/${target.z()} in world ${targetWorld.name}.",
                e,
            )
            PasteResult.Failed(e)
        } catch (e: RuntimeException) {
            logger.log(
                Level.WARNING,
                "Unexpected failure while pasting an asset for player ${player.uniqueId} " +
                    "at ${target.x()}/${target.y()}/${target.z()} in world ${targetWorld.name}.",
                e,
            )
            PasteResult.Failed(e)
        }
    }
}
