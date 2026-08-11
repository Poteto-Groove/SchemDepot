package io.github.potetogroove.schemdepot.storage

import io.github.potetogroove.schemdepot.asset.Asset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class SqliteAssetRepositoryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var repository: SqliteAssetRepository

    @BeforeEach
    fun setUp() {
        val database = Database(File(tempDir, "assets.db"))
        database.openConnection().use { connection -> DatabaseMigration.migrate(connection) }
        repository = SqliteAssetRepository(database)
    }

    private fun sampleAsset(
        id: UUID = UUID.randomUUID(),
        name: String = "OakTree",
        normalizedName: String = "oaktree",
        authorUuid: UUID = UUID.randomUUID(),
        authorName: String = "warasugi",
        createdAt: Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS),
        updatedAt: Instant = createdAt,
        sizeX: Int = 13,
        sizeY: Int = 18,
        sizeZ: Int = 12,
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

    @Test
    fun `insert then findById and findByName round-trip all fields`() {
        val asset = sampleAsset()
        repository.insert(asset)

        val byId = repository.findById(asset.id)
        val byName = repository.findByName(asset.normalizedName)

        assertEquals(asset, byId)
        assertEquals(asset, byName)
    }

    @Test
    fun `findByName and findById return null for unknown assets`() {
        assertNull(repository.findByName("doesnotexist"))
        assertNull(repository.findById(UUID.randomUUID()))
    }

    @Test
    fun `normalized_name unique constraint rejects a second insert with the same normalized name`() {
        repository.insert(sampleAsset(name = "OakTree", normalizedName = "oaktree"))

        val duplicate = sampleAsset(name = "oaktree", normalizedName = "oaktree")

        assertThrows(SQLException::class.java) {
            repository.insert(duplicate)
        }

        assertEquals(1, repository.count())
    }

    @Test
    fun `schematic_file unique constraint rejects a second insert with the same backing file`() {
        val sharedFile = "shared.schem"
        repository.insert(sampleAsset(normalizedName = "one", schematicFile = sharedFile))

        assertThrows(SQLException::class.java) {
            repository.insert(sampleAsset(normalizedName = "two", schematicFile = sharedFile))
        }
    }

    @Test
    fun `existsByName reflects insert state`() {
        assertFalse(repository.existsByName("oaktree"))
        repository.insert(sampleAsset())
        assertTrue(repository.existsByName("oaktree"))
    }

    @Test
    fun `count and list support pagination in created_at order`() {
        val base = Instant.parse("2026-01-01T00:00:00Z")
        val names = listOf("alpha", "bravo", "charlie", "delta", "echo")
        names.forEachIndexed { index, name ->
            repository.insert(
                sampleAsset(
                    name = name,
                    normalizedName = name,
                    createdAt = base.plusSeconds(index.toLong()),
                ),
            )
        }

        assertEquals(5L, repository.count())

        val firstPage = repository.list(limit = 2, offset = 0)
        val secondPage = repository.list(limit = 2, offset = 2)
        val thirdPage = repository.list(limit = 2, offset = 4)
        val beyondEnd = repository.list(limit = 2, offset = 5)

        assertEquals(listOf("alpha", "bravo"), firstPage.map { it.name })
        assertEquals(listOf("charlie", "delta"), secondPage.map { it.name })
        assertEquals(listOf("echo"), thirdPage.map { it.name })
        assertTrue(beyondEnd.isEmpty())
    }

    @Test
    fun `list on an empty repository returns an empty list and count is zero`() {
        assertEquals(0L, repository.count())
        assertTrue(repository.list(limit = 8, offset = 0).isEmpty())
    }

    @Test
    fun `updateName changes name normalized_name and updated_at but leaves schematic_file and created_at unchanged`() {
        val asset = sampleAsset(name = "OakTree", normalizedName = "oaktree")
        repository.insert(asset)

        val newUpdatedAt = asset.updatedAt.plusSeconds(60)
        repository.updateName(asset.id, "LargeOak", "largeoak", newUpdatedAt)

        val updated = repository.findById(asset.id)
        assertNotNull(updated)
        assertEquals("LargeOak", updated!!.name)
        assertEquals("largeoak", updated.normalizedName)
        assertEquals(newUpdatedAt, updated.updatedAt)
        assertEquals(asset.schematicFile, updated.schematicFile)
        assertEquals(asset.createdAt, updated.createdAt)

        assertNull(repository.findByName("oaktree"))
        assertNotNull(repository.findByName("largeoak"))
    }

    @Test
    fun `delete removes the row`() {
        val asset = sampleAsset()
        repository.insert(asset)

        repository.delete(asset.id)

        assertNull(repository.findById(asset.id))
        assertEquals(0L, repository.count())
    }
}
