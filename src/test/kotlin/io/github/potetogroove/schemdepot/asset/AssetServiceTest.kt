package io.github.potetogroove.schemdepot.asset

import io.github.potetogroove.schemdepot.config.SchemDepotConfig
import io.github.potetogroove.schemdepot.permission.Permissions
import io.github.potetogroove.schemdepot.storage.SchematicStorage
import io.github.potetogroove.schemdepot.worldedit.ClipboardService
import io.github.potetogroove.schemdepot.worldedit.PasteService
import io.github.potetogroove.schemdepot.worldedit.WorldEditFacade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor

/**
 * Unit tests for [AssetService] using a fake [AssetRepository], a real [SchematicStorage] backed
 * by a temp directory, and synchronous executor/dispatcher (docs/SchemDepot_DESIGN.md SS27.1).
 *
 * [ClipboardService]/[PasteService] cannot be exercised without a live WorldEdit/FAWE platform
 * (see their own KDoc), so this suite only calls the internal worker-thread cores of
 * [AssetService] that are documented as safe to call without a Bukkit `Player`
 * ([AssetService.performAdd], [AssetService.resolveAndLoadClipboard]), plus the fully
 * Bukkit-free public methods ([AssetService.list], [AssetService.info], [AssetService.rename],
 * [AssetService.remove], [AssetService.loadIndexBlocking]). Full end-to-end [AssetService.add]/
 * [AssetService.paste] coverage (including a live `Player`) is deferred to the Phase 7
 * integration pass, per the task instructions.
 */
class AssetServiceTest {

    @TempDir
    lateinit var dataDirectory: Path

    private lateinit var schematicStorage: SchematicStorage
    private lateinit var fakeRepository: FakeAssetRepository
    private lateinit var service: AssetService

    @BeforeEach
    fun setUp() {
        schematicStorage = SchematicStorage(dataDirectory)
        schematicStorage.initializeDirectories()
        fakeRepository = FakeAssetRepository()
        service = buildService(fakeRepository, schematicStorage, testConfig())
    }

    private fun buildService(
        repository: AssetRepository,
        storage: SchematicStorage,
        config: SchemDepotConfig,
    ): AssetService {
        val facade = WorldEditFacade()
        return AssetService(
            repository = repository,
            schematicStorage = storage,
            clipboardService = ClipboardService(facade),
            pasteService = PasteService(facade),
            config = config,
            workerExecutor = Executor { it.run() },
            mainThreadDispatcher = { it.run() },
        )
    }

    private fun testConfig(pageSize: Int = 8, maxVolume: Long = 0L): SchemDepotConfig =
        SchemDepotConfig(
            storage = SchemDepotConfig.Storage(
                databaseFile = "assets.db",
                schematicsDirectory = "schematics",
                tempDirectory = "tmp",
                trashDirectory = "trash",
            ),
            list = SchemDepotConfig.ListSettings(pageSize = pageSize),
            paste = SchemDepotConfig.PasteSettings(
                ignoreAirByDefault = false,
                includeEntities = false,
                includeBiomes = false,
            ),
            limits = SchemDepotConfig.Limits(maxVolume = maxVolume),
        )

    private fun sampleAsset(
        id: UUID = UUID.randomUUID(),
        name: String = "OakTree",
        normalizedName: String = "oaktree",
        authorUuid: UUID = UUID.randomUUID(),
        authorName: String = "warasugi",
        createdAt: Instant = Instant.now(),
        updatedAt: Instant = createdAt,
        sizeX: Int = 1,
        sizeY: Int = 1,
        sizeZ: Int = 1,
        schematicFile: String = "$id.schem",
    ): Asset = Asset(
        id = id,
        name = name,
        normalizedName = normalizedName,
        authorUuid = authorUuid,
        authorName = authorName,
        createdAt = createdAt,
        updatedAt = updatedAt,
        sizeX = sizeX,
        sizeY = sizeY,
        sizeZ = sizeZ,
        schematicFile = schematicFile,
    )

    private fun filesIn(directory: Path): List<Path> = Files.newDirectoryStream(directory).use { it.toList() }

    // -------------------------------------------------------------------------------------
    // AC-03 - duplicate names (case-insensitive), no orphan files
    // -------------------------------------------------------------------------------------

    @Test
    fun `AC-03 a case-insensitive duplicate add is rejected and leaves no orphan files`() {
        val firstName = AssetName.validate("OakTree") as AssetNameResult.Valid
        val secondName = AssetName.validate("oaktree") as AssetNameResult.Valid
        val dimensions = ClipboardService.ClipboardDimensions(1, 1, 1)

        val firstResult = service.performAdd(
            UUID.randomUUID(),
            firstName,
            UUID.randomUUID(),
            "Alice",
            dimensions,
        ) { output -> output.write("first".toByteArray()) }
        assertTrue(firstResult is AddResult.Success, "expected Success but was $firstResult")

        val secondResult = service.performAdd(
            UUID.randomUUID(),
            secondName,
            UUID.randomUUID(),
            "Bob",
            dimensions,
        ) { output -> output.write("second".toByteArray()) }
        assertTrue(secondResult is AddResult.DuplicateName, "expected DuplicateName but was $secondResult")

        assertEquals(1, filesIn(schematicStorage.schematicsDirectory).size)
        assertTrue(
            filesIn(schematicStorage.tempDirectory).isEmpty(),
            "expected no leftover temp files after a rejected duplicate add",
        )
    }

    // -------------------------------------------------------------------------------------
    // DB insert failure after a successful schematic write
    // -------------------------------------------------------------------------------------

    @Test
    fun `a database insert failure after the schematic write deletes the file and is not reported as success`() {
        fakeRepository.insertException = SQLException("simulated insert failure")
        val validName = AssetName.validate("OakTree") as AssetNameResult.Valid
        val dimensions = ClipboardService.ClipboardDimensions(2, 2, 2)

        val result = service.performAdd(
            UUID.randomUUID(),
            validName,
            UUID.randomUUID(),
            "Alice",
            dimensions,
        ) { output -> output.write("data".toByteArray()) }

        assertTrue(result is AddResult.InternalError, "expected InternalError but was $result")
        assertTrue(
            filesIn(schematicStorage.schematicsDirectory).isEmpty(),
            "the orphan schematic must be removed after a failed insert",
        )
        assertTrue(
            filesIn(schematicStorage.trashDirectory).isEmpty(),
            "the orphan schematic must be permanently deleted from trash, not left behind",
        )
        assertEquals(0L, fakeRepository.count())
    }

    // -------------------------------------------------------------------------------------
    // SS20.1 - two simultaneous adds of the same name: the loser gets a duplicate-name error
    // -------------------------------------------------------------------------------------

    @Test
    fun `an insert that loses a concurrent race for the same name is reported as DuplicateName`() {
        // Models exactly what happens with two worker threads: this call's existsByName pre-check
        // runs *before* the other player's insert commits (so it sees nothing), and the commit
        // lands in between, making this insert fail on the UNIQUE(normalized_name) constraint.
        val winner = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorName = "Bob")
        fakeRepository.insertHandler = { _ ->
            fakeRepository.assets[winner.id] = winner
            throw SQLException("[SQLITE_CONSTRAINT_UNIQUE] UNIQUE constraint failed: assets.normalized_name")
        }

        val validName = AssetName.validate("OakTree") as AssetNameResult.Valid
        val result = service.performAdd(
            UUID.randomUUID(),
            validName,
            UUID.randomUUID(),
            "Alice",
            ClipboardService.ClipboardDimensions(2, 2, 2),
        ) { output -> output.write("data".toByteArray()) }

        assertTrue(result is AddResult.DuplicateName, "expected DuplicateName but was $result")
        assertEquals("OakTree", (result as AddResult.DuplicateName).name)

        // AC-03 still holds on this path: the loser's schematic must not survive anywhere.
        assertTrue(
            filesIn(schematicStorage.schematicsDirectory).isEmpty(),
            "the loser's schematic must be removed from schematics/",
        )
        assertTrue(
            filesIn(schematicStorage.trashDirectory).isEmpty(),
            "the loser's schematic must be permanently deleted from trash/, not left behind",
        )
        assertTrue(
            filesIn(schematicStorage.tempDirectory).isEmpty(),
            "no temp file may be left behind",
        )
        assertEquals(1L, fakeRepository.count(), "only the winning registration may remain")
    }

    @Test
    fun `an insert failure with no competing registration is still an InternalError`() {
        // Guards the new duplicate-name branch against swallowing genuine database failures.
        fakeRepository.insertException = SQLException("disk I/O error")
        val validName = AssetName.validate("OakTree") as AssetNameResult.Valid

        val result = service.performAdd(
            UUID.randomUUID(),
            validName,
            UUID.randomUUID(),
            "Alice",
            ClipboardService.ClipboardDimensions(2, 2, 2),
        ) { output -> output.write("data".toByteArray()) }

        assertTrue(result is AddResult.InternalError, "expected InternalError but was $result")
    }

    // -------------------------------------------------------------------------------------
    // 0-row UPDATE/DELETE must not be reported as success (registry is the source of truth)
    // -------------------------------------------------------------------------------------

    @Test
    fun `performRename maps a zero-row update to NotFound instead of Success`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        // The row disappears from the database behind the service's back.
        fakeRepository.assets.remove(asset.id)

        val newName = AssetName.validate("LargeOak") as AssetNameResult.Valid
        val result = service.performRename(asset, newName, ownerUuid)

        assertTrue(result is RenameResult.NotFound, "expected NotFound but was $result")
    }

    @Test
    fun `rename of a row that vanished does not publish a ghost entry into the index`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        fakeRepository.updateNameRowsOverride = 0

        var result: RenameResult? = null
        service.rename(ownerUuid, "OakTree", "LargeOak", { true }) { result = it }
        assertTrue(result is RenameResult.NotFound, "expected NotFound but was $result")

        // The new name must never have entered the index: looking it up has to miss.
        var lookupNewName: RenameResult? = null
        service.rename(ownerUuid, "LargeOak", "Whatever", { true }) { lookupNewName = it }
        assertTrue(
            lookupNewName is RenameResult.NotFound,
            "the new name must not exist in the index but was $lookupNewName",
        )
    }

    @Test
    fun `performRemove maps a zero-row delete to NotFound instead of Success`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        // The row disappears from the database behind the service's back.
        fakeRepository.assets.remove(asset.id)

        val result = service.performRemove(asset, ownerUuid)

        assertTrue(result is RemoveResult.NotFound, "expected NotFound but was $result")
    }

    @Test
    fun `remove of a row that vanished reports NotFound to the caller`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        fakeRepository.deleteRowsOverride = 0

        var result: RemoveResult? = null
        service.remove(ownerUuid, "OakTree", { true }) { result = it }

        assertTrue(result is RemoveResult.NotFound, "expected NotFound but was $result")
    }

    // -------------------------------------------------------------------------------------
    // AC-09 - rename never changes schematic_file / the backing file
    // -------------------------------------------------------------------------------------

    @Test
    fun `AC-09 rename changes the display name but never the backing schematic_file`() {
        val id = UUID.randomUUID()
        val authorUuid = UUID.randomUUID()
        val original = sampleAsset(id = id, name = "OakTree", normalizedName = "oaktree", authorUuid = authorUuid)
        fakeRepository.assets[id] = original
        schematicStorage.write(id) { output -> output.write("data".toByteArray()) }

        val newName = AssetName.validate("LargeOak") as AssetNameResult.Valid
        val result = service.performRename(original, newName, authorUuid)

        val success = result as? RenameResult.Success
        assertTrue(success != null, "expected Success but was $result")
        assertEquals("LargeOak", success!!.asset.name)
        assertEquals("largeoak", success.asset.normalizedName)
        assertEquals(original.schematicFile, success.asset.schematicFile)

        // The on-disk file is untouched by a rename - still resolvable under the same id.
        val resolved = schematicStorage.resolveForRead(id)
        assertTrue(resolved is SchematicStorage.ReadResolution.Found, "expected Found but was $resolved")
    }

    @Test
    fun `rename rejects a reserved new name regardless of case`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        var result: RenameResult? = null
        service.rename(ownerUuid, "OakTree", "LIST", { true }) { result = it }

        assertTrue(result is RenameResult.ReservedName, "expected ReservedName but was $result")
    }

    @Test
    fun `rename rejects an invalid new name format`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        var result: RenameResult? = null
        service.rename(ownerUuid, "OakTree", "bad name!", { true }) { result = it }

        assertTrue(result is RenameResult.InvalidName, "expected InvalidName but was $result")
    }

    // -------------------------------------------------------------------------------------
    // AC-10 - .own/.any ownership decided strictly by UUID
    // -------------------------------------------------------------------------------------

    @Test
    fun `AC-10 rename permits the UUID owner with only rename own even under a different display name`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(
            name = "OakTree",
            normalizedName = "oaktree",
            authorUuid = ownerUuid,
            authorName = "Alice",
        )
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        var result: RenameResult? = null
        // The caller is only ever identified by UUID; the permission lambda knows nothing about
        // display names, so a match here can only come from the UUID comparison in AssetService.
        service.rename(ownerUuid, "OakTree", "LargeOak", { it == Permissions.RENAME_OWN }) { result = it }

        assertTrue(result is RenameResult.Success, "expected Success but was $result")
    }

    @Test
    fun `AC-10 rename denies a different-UUID caller with only rename own but allows rename any`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        val otherUuid = UUID.randomUUID()

        var denied: RenameResult? = null
        service.rename(otherUuid, "OakTree", "LargeOak", { it == Permissions.RENAME_OWN }) { denied = it }
        assertEquals(RenameResult.NoPermission, denied)

        var allowed: RenameResult? = null
        service.rename(otherUuid, "OakTree", "LargeOak", { it == Permissions.RENAME_ANY }) { allowed = it }
        assertTrue(allowed is RenameResult.Success, "expected Success but was $allowed")
    }

    @Test
    fun `AC-10 remove permits the UUID owner with only remove own even under a different display name`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(
            name = "OakTree",
            normalizedName = "oaktree",
            authorUuid = ownerUuid,
            authorName = "Alice",
        )
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        var result: RemoveResult? = null
        service.remove(ownerUuid, "OakTree", { it == Permissions.REMOVE_OWN }) { result = it }

        assertTrue(result is RemoveResult.Success, "expected Success but was $result")
    }

    @Test
    fun `AC-10 remove denies a different-UUID caller with only remove own but allows remove any`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        val otherUuid = UUID.randomUUID()

        var denied: RemoveResult? = null
        service.remove(otherUuid, "OakTree", { it == Permissions.REMOVE_OWN }) { denied = it }
        assertEquals(RemoveResult.NoPermission, denied)
        assertTrue(fakeRepository.assets.containsKey(asset.id), "denied remove must not delete the asset")

        var allowed: RemoveResult? = null
        service.remove(otherUuid, "OakTree", { it == Permissions.REMOVE_ANY }) { allowed = it }
        assertTrue(allowed is RemoveResult.Success, "expected Success but was $allowed")
    }

    // -------------------------------------------------------------------------------------
    // AC-11 - missing backing schematic file
    // -------------------------------------------------------------------------------------

    @Test
    fun `AC-11 resolving a clipboard for an asset with no backing file reports Unavailable`() {
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree")
        // Deliberately never write a schematic file for this asset's id.

        val result = service.resolveAndLoadClipboard(asset)

        assertEquals(AssetService.ClipboardLoad.Unavailable, result)
    }

    // -------------------------------------------------------------------------------------
    // mapPasteOutcome - WorldEdit/FAWE rejecting or failing a paste must not be reported as a
    // generic InternalError (UX fix: a region-restriction denial is not a SchemDepot bug and must
    // not tell the player to "contact an administrator"). PasteService.pasteForPlayer itself
    // cannot be exercised without a live Bukkit Player/WorldEdit platform (see its own KDoc), so
    // this suite covers the pure PasteService.PasteResult -> AssetResult.PasteResult translation
    // exposed as AssetService.mapPasteOutcome instead.
    // -------------------------------------------------------------------------------------

    @Test
    fun `mapPasteOutcome maps a WorldEditException rejection to PasteRejected with its message as the safe reason`() {
        // worldedit-bukkit is only `testRuntimeOnly` here (see build.gradle.kts), so this test
        // cannot reference com.sk89q.worldedit.WorldEditException at compile time. A plain
        // Exception stands in for it: mapPasteOutcome only forwards whatever safeReason
        // PasteService.pasteForPlayer already decided was safe (its own KDoc/tests own the
        // decision of *when* a reason is safe) - the exception's runtime type is irrelevant here.
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree")
        val cause = Exception("No allowed region for this edit.")

        val result = service.mapPasteOutcome(
            asset,
            PasteService.PasteResult.Rejected(cause, safeReason = cause.message),
        )

        val rejected = result as? PasteResult.PasteRejected
        assertTrue(rejected != null, "expected PasteRejected but was $result")
        assertEquals("No allowed region for this edit.", rejected!!.safeReason)
        assertEquals(cause, rejected.cause)
    }

    @Test
    fun `mapPasteOutcome maps a non-WorldEditException rejection (e g a FAWE exception) to PasteRejected with no reason`() {
        // Models FAWE's FaweException: a RuntimeException that is *not* a WorldEditException
        // (verified via javap - see PasteService.pasteForPlayer's catch clauses), so
        // PasteService.pasteForPlayer never has a safe message to hand back for it.
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree")
        val cause = IllegalStateException("No allowed region (bypass with /wea, or disable region-restrictions)")

        val result = service.mapPasteOutcome(
            asset,
            PasteService.PasteResult.Rejected(cause, safeReason = null),
        )

        val rejected = result as? PasteResult.PasteRejected
        assertTrue(rejected != null, "expected PasteRejected but was $result")
        assertEquals(null, rejected!!.safeReason)
        assertEquals(cause, rejected.cause)
    }

    @Test
    fun `mapPasteOutcome maps a Success outcome to Success with the original asset`() {
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree")

        val result = service.mapPasteOutcome(asset, PasteService.PasteResult.Success)

        val success = result as? PasteResult.Success
        assertTrue(success != null, "expected Success but was $result")
        assertEquals(asset, success!!.asset)
    }

    // -------------------------------------------------------------------------------------
    // list pagination (config.list.pageSize)
    // -------------------------------------------------------------------------------------

    @Test
    fun `list paginates according to config list pageSize and clamps out-of-range pages`() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val names = listOf("alpha", "bravo", "charlie", "delta", "echo")
        names.forEachIndexed { index, name ->
            val asset = sampleAsset(name = name, normalizedName = name, createdAt = base.plusSeconds(index.toLong()))
            fakeRepository.assets[asset.id] = asset
        }
        val pagedService = buildService(fakeRepository, schematicStorage, testConfig(pageSize = 2))

        var firstPage: ListResult? = null
        pagedService.list(1, { true }) { firstPage = it }
        val firstSuccess = firstPage as? ListResult.Success
        assertTrue(firstSuccess != null, "expected Success but was $firstPage")
        assertEquals(listOf("alpha", "bravo"), firstSuccess!!.assets.map { it.name })
        assertEquals(3, firstSuccess.totalPages)
        assertEquals(5L, firstSuccess.totalCount)

        var lastPage: ListResult? = null
        pagedService.list(3, { true }) { lastPage = it }
        val lastSuccess = lastPage as? ListResult.Success
        assertTrue(lastSuccess != null, "expected Success but was $lastPage")
        assertEquals(listOf("echo"), lastSuccess!!.assets.map { it.name })

        var overflowPage: ListResult? = null
        pagedService.list(99, { true }) { overflowPage = it }
        val overflowSuccess = overflowPage as? ListResult.Success
        assertTrue(overflowSuccess != null, "expected Success but was $overflowPage")
        assertEquals(3, overflowSuccess!!.page, "an out-of-range page must be clamped, not treated as an error")
    }

    @Test
    fun `list denies a caller without schemdepot list`() {
        var result: ListResult? = null
        service.list(1, { false }) { result = it }
        assertEquals(ListResult.NoPermission, result)
    }

    // -------------------------------------------------------------------------------------
    // remove success clears both the index and the repository
    // -------------------------------------------------------------------------------------

    @Test
    fun `remove success clears the asset from both the in-memory index and the repository`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        var removeResult: RemoveResult? = null
        service.remove(ownerUuid, "OakTree", { true }) { removeResult = it }
        assertTrue(removeResult is RemoveResult.Success, "expected Success but was $removeResult")

        assertFalse(fakeRepository.assets.containsKey(asset.id), "asset must be gone from the repository")

        // The in-memory index must also be cleared: a subsequent rename attempt resolves through
        // the index and must report NotFound rather than operating on stale data.
        var renameAfterRemove: RenameResult? = null
        service.rename(ownerUuid, "OakTree", "Whatever", { true }) { renameAfterRemove = it }
        assertTrue(
            renameAfterRemove is RenameResult.NotFound,
            "expected NotFound (index cleared) but was $renameAfterRemove",
        )
    }

    // -------------------------------------------------------------------------------------
    // SS20/SS30-20/21 - an unexpected Throwable escaping a worker-thread operation must never
    // kill the worker thread; it must always produce an InternalError result for the callback.
    // -------------------------------------------------------------------------------------

    @Test
    fun `list reports InternalError instead of propagating an Error thrown by the repository`() {
        fakeRepository.countError = NoSuchMethodError("simulated linkage failure")

        var result: ListResult? = null
        service.list(1, { true }) { result = it }

        assertTrue(result is ListResult.InternalError, "expected InternalError but was $result")
    }

    @Test
    fun `list reports InternalError instead of propagating an unexpected RuntimeException from the repository`() {
        fakeRepository.countError = IllegalStateException("simulated unexpected failure")

        var result: ListResult? = null
        service.list(1, { true }) { result = it }

        assertTrue(result is ListResult.InternalError, "expected InternalError but was $result")
    }

    @Test
    fun `info reports InternalError instead of propagating an Error thrown by the repository`() {
        fakeRepository.findByNameError = NoSuchMethodError("simulated linkage failure")

        var result: InfoResult? = null
        service.info("OakTree", { true }) { result = it }

        assertTrue(result is InfoResult.InternalError, "expected InternalError but was $result")
    }

    @Test
    fun `info reports InternalError instead of propagating an unexpected RuntimeException from the repository`() {
        fakeRepository.findByNameError = IllegalStateException("simulated unexpected failure")

        var result: InfoResult? = null
        service.info("OakTree", { true }) { result = it }

        assertTrue(result is InfoResult.InternalError, "expected InternalError but was $result")
    }

    @Test
    fun `rename reports InternalError instead of propagating an Error thrown by the repository`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        fakeRepository.existsByNameError = NoSuchMethodError("simulated linkage failure")

        var result: RenameResult? = null
        service.rename(ownerUuid, "OakTree", "LargeOak", { true }) { result = it }

        assertTrue(result is RenameResult.InternalError, "expected InternalError but was $result")
    }

    @Test
    fun `rename reports InternalError instead of propagating an unexpected RuntimeException from the repository`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        fakeRepository.existsByNameError = IllegalStateException("simulated unexpected failure")

        var result: RenameResult? = null
        service.rename(ownerUuid, "OakTree", "LargeOak", { true }) { result = it }

        assertTrue(result is RenameResult.InternalError, "expected InternalError but was $result")
    }

    @Test
    fun `remove reports InternalError instead of propagating an Error thrown by the repository`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        fakeRepository.deleteError = NoSuchMethodError("simulated linkage failure")

        var result: RemoveResult? = null
        service.remove(ownerUuid, "OakTree", { true }) { result = it }

        assertTrue(result is RemoveResult.InternalError, "expected InternalError but was $result")
    }

    @Test
    fun `remove reports InternalError instead of propagating an unexpected RuntimeException from the repository`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        fakeRepository.deleteError = IllegalStateException("simulated unexpected failure")

        var result: RemoveResult? = null
        service.remove(ownerUuid, "OakTree", { true }) { result = it }

        assertTrue(result is RemoveResult.InternalError, "expected InternalError but was $result")
    }

    // -------------------------------------------------------------------------------------
    // suggestNames (SS18 tab completion, task 1 index consolidation)
    // -------------------------------------------------------------------------------------

    @Test
    fun `suggestNames returns display names matching a prefix regardless of case`() {
        val alice = UUID.randomUUID()
        fakeRepository.assets[UUID.randomUUID()] = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = alice)
        fakeRepository.assets[UUID.randomUUID()] = sampleAsset(name = "OakStump", normalizedName = "oakstump", authorUuid = alice)
        fakeRepository.assets[UUID.randomUUID()] = sampleAsset(name = "BirchTree", normalizedName = "birchtree", authorUuid = alice)
        service.loadIndexBlocking()

        assertEquals(listOf("OakStump", "OakTree"), service.suggestNames("Oak"))
        assertEquals(listOf("OakStump", "OakTree"), service.suggestNames("oak"))
        assertEquals(listOf("OakStump", "OakTree"), service.suggestNames("OAK"))
        assertEquals(emptyList<String>(), service.suggestNames("pine"))
    }

    @Test
    fun `suggestNames reflects a rename by dropping the old name and offering the new one`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()

        var result: RenameResult? = null
        service.rename(ownerUuid, "OakTree", "LargeOak", { true }) { result = it }
        assertTrue(result is RenameResult.Success, "expected Success but was $result")

        assertEquals(emptyList<String>(), service.suggestNames("OakTree"))
        assertEquals(listOf("LargeOak"), service.suggestNames("Large"))
    }

    @Test
    fun `suggestNames no longer offers a name after it is removed`() {
        val ownerUuid = UUID.randomUUID()
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree", authorUuid = ownerUuid)
        fakeRepository.assets[asset.id] = asset
        service.loadIndexBlocking()
        assertEquals(listOf("OakTree"), service.suggestNames("Oak"))

        var result: RemoveResult? = null
        service.remove(ownerUuid, "OakTree", { true }) { result = it }
        assertTrue(result is RemoveResult.Success, "expected Success but was $result")

        assertEquals(emptyList<String>(), service.suggestNames("Oak"))
    }

    /** In-memory fake [AssetRepository] for unit testing [AssetService] without SQLite. */
    private class FakeAssetRepository : AssetRepository {
        val assets: MutableMap<UUID, Asset> = linkedMapOf()

        /** When set, [insert] throws this instead of storing the asset (simulates a DB failure). */
        var insertException: Exception? = null

        /**
         * When set, [insert] delegates to this instead of storing the asset. Lets a test model a
         * concurrent writer that commits the same name and then makes *this* insert fail.
         */
        var insertHandler: ((Asset) -> Unit)? = null

        /** Number of rows the next [updateName] call reports as updated; `null` = real behaviour. */
        var updateNameRowsOverride: Int? = null

        /** Number of rows the next [delete] call reports as deleted; `null` = real behaviour. */
        var deleteRowsOverride: Int? = null

        /** When set, [count] throws this instead of returning normally (simulates any Throwable). */
        var countError: Throwable? = null

        /** When set, [findByName] throws this instead of returning normally. */
        var findByNameError: Throwable? = null

        /** When set, [existsByName] throws this instead of returning normally. */
        var existsByNameError: Throwable? = null

        /** When set, [delete] throws this instead of returning normally. */
        var deleteError: Throwable? = null

        override fun findByName(normalizedName: String): Asset? {
            findByNameError?.let { throw it }
            return assets.values.firstOrNull { it.normalizedName == normalizedName }
        }

        override fun findById(id: UUID): Asset? = assets[id]

        override fun list(limit: Int, offset: Int): List<Asset> =
            assets.values
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
                .drop(offset)
                .take(limit)

        override fun count(): Long {
            countError?.let { throw it }
            return assets.size.toLong()
        }

        override fun existsByName(normalizedName: String): Boolean {
            existsByNameError?.let { throw it }
            return assets.values.any { it.normalizedName == normalizedName }
        }

        override fun insert(asset: Asset) {
            insertException?.let { throw it }
            insertHandler?.let { handler ->
                handler(asset)
                return
            }
            assets[asset.id] = asset
        }

        override fun updateName(
            id: UUID,
            name: String,
            normalizedName: String,
            updatedAt: Instant,
        ): Int {
            updateNameRowsOverride?.let { return it }
            val existing = assets[id] ?: return 0
            assets[id] = existing.copy(name = name, normalizedName = normalizedName, updatedAt = updatedAt)
            return 1
        }

        override fun delete(id: UUID): Int {
            deleteError?.let { throw it }
            deleteRowsOverride?.let { return it }
            return if (assets.remove(id) != null) 1 else 0
        }
    }
}
