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

    /** In-memory fake [AssetRepository] for unit testing [AssetService] without SQLite. */
    private class FakeAssetRepository : AssetRepository {
        val assets: MutableMap<UUID, Asset> = linkedMapOf()

        /** When set, [insert] throws this instead of storing the asset (simulates a DB failure). */
        var insertException: Exception? = null

        override fun findByName(normalizedName: String): Asset? =
            assets.values.firstOrNull { it.normalizedName == normalizedName }

        override fun findById(id: UUID): Asset? = assets[id]

        override fun list(limit: Int, offset: Int): List<Asset> =
            assets.values
                .sortedWith(compareBy({ it.createdAt }, { it.id }))
                .drop(offset)
                .take(limit)

        override fun count(): Long = assets.size.toLong()

        override fun existsByName(normalizedName: String): Boolean =
            assets.values.any { it.normalizedName == normalizedName }

        override fun insert(asset: Asset) {
            insertException?.let { throw it }
            assets[asset.id] = asset
        }

        override fun updateName(id: UUID, name: String, normalizedName: String, updatedAt: Instant) {
            val existing = assets[id] ?: return
            assets[id] = existing.copy(name = name, normalizedName = normalizedName, updatedAt = updatedAt)
        }

        override fun delete(id: UUID) {
            assets.remove(id)
        }
    }
}
