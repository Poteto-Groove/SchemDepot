package io.github.potetogroove.schemdepot.storage

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class SchematicStorageTest {

    @TempDir
    lateinit var dataDirectory: Path

    private lateinit var storage: SchematicStorage

    @BeforeEach
    fun setUp() {
        storage = SchematicStorage(dataDirectory)
    }

    @Test
    fun `initializeDirectories creates schematics, tmp, and trash directories`() {
        assertFalse(Files.exists(storage.schematicsDirectory))
        assertFalse(Files.exists(storage.tempDirectory))
        assertFalse(Files.exists(storage.trashDirectory))

        storage.initializeDirectories()

        assertTrue(Files.isDirectory(storage.schematicsDirectory))
        assertTrue(Files.isDirectory(storage.tempDirectory))
        assertTrue(Files.isDirectory(storage.trashDirectory))
    }

    @Test
    fun `write moves content through tmp into schematics directory`() {
        storage.initializeDirectories()
        val id = UUID.randomUUID()
        val content = "hello schematic".toByteArray()

        val result = storage.write(id) { output -> output.write(content) }

        val success = result as? SchematicStorage.WriteResult.Success
        assertTrue(success != null, "expected Success but was $result")
        val targetPath = storage.schematicsDirectory.resolve("$id.schem")
        assertEquals(targetPath, success!!.path)
        assertTrue(Files.exists(targetPath))
        assertArrayEquals(content, Files.readAllBytes(targetPath))

        // No leftover temp file after a successful write.
        Files.newDirectoryStream(storage.tempDirectory).use { stream ->
            assertFalse(stream.iterator().hasNext(), "expected no leftover temp files")
        }
    }

    @Test
    fun `write cleans up tmp file and leaves no target when writer throws`() {
        storage.initializeDirectories()
        val id = UUID.randomUUID()

        val result = storage.write(id) { output ->
            output.write("partial".toByteArray())
            throw IOException("simulated failure")
        }

        assertTrue(result is SchematicStorage.WriteResult.Failed, "expected Failed but was $result")

        val targetPath = storage.schematicsDirectory.resolve("$id.schem")
        assertFalse(Files.exists(targetPath), "no partial file should exist in schematics/")

        Files.newDirectoryStream(storage.tempDirectory).use { stream ->
            assertFalse(stream.iterator().hasNext(), "expected no leftover temp files")
        }
    }

    @Test
    fun `write rejects a second write for the same id instead of overwriting`() {
        storage.initializeDirectories()
        val id = UUID.randomUUID()
        val original = "original content".toByteArray()
        val attempted = "attempted overwrite".toByteArray()

        val first = storage.write(id) { output -> output.write(original) }
        assertTrue(first is SchematicStorage.WriteResult.Success)

        val second = storage.write(id) { output -> output.write(attempted) }
        assertTrue(
            second is SchematicStorage.WriteResult.AlreadyExists,
            "expected AlreadyExists but was $second",
        )

        val targetPath = storage.schematicsDirectory.resolve("$id.schem")
        assertArrayEquals(original, Files.readAllBytes(targetPath))

        Files.newDirectoryStream(storage.tempDirectory).use { stream ->
            assertFalse(stream.iterator().hasNext(), "expected no leftover temp files")
        }
    }

    @Test
    fun `resolveForReadPath rejects paths outside the schematics directory`() {
        storage.initializeDirectories()

        // A path that is a sibling of schematics/ (e.g. trash/) must be rejected even though it
        // is still inside the plugin data directory.
        val outsidePath = storage.trashDirectory.resolve("not-really-a-schematic.schem")

        val result = storage.resolveForReadPath(outsidePath)

        assertTrue(result is SchematicStorage.ReadResolution.Rejected, "expected Rejected but was $result")
    }

    @Test
    fun `resolveForReadPath rejects a traversal attempt escaping the schematics directory`() {
        storage.initializeDirectories()

        val traversalPath = storage.schematicsDirectory.resolve("../../etc/passwd")

        val result = storage.resolveForReadPath(traversalPath)

        assertTrue(result is SchematicStorage.ReadResolution.Rejected, "expected Rejected but was $result")
    }

    @Test
    fun `resolveForRead returns NotFound for a missing asset instead of throwing`() {
        storage.initializeDirectories()

        val result = storage.resolveForRead(UUID.randomUUID())

        assertTrue(result is SchematicStorage.ReadResolution.NotFound, "expected NotFound but was $result")
    }

    @Test
    fun `resolveForRead finds an existing schematic within the schematics directory`() {
        storage.initializeDirectories()
        val id = UUID.randomUUID()
        val writer: (OutputStream) -> Unit = { output -> output.write("data".toByteArray()) }
        storage.write(id, writer)

        val result = storage.resolveForRead(id)

        val found = result as? SchematicStorage.ReadResolution.Found
        assertTrue(found != null, "expected Found but was $result")
        assertEquals(storage.schematicsDirectory.resolve("$id.schem"), found!!.path)
    }

    @Test
    fun `move to trash then delete from trash lifecycle`() {
        storage.initializeDirectories()
        val id = UUID.randomUUID()
        storage.write(id) { output -> output.write("trash me".toByteArray()) }

        val moveResult = storage.moveToTrash(id)
        assertTrue(moveResult is SchematicStorage.TrashMoveResult.Moved, "expected Moved but was $moveResult")

        val schematicPath = storage.schematicsDirectory.resolve("$id.schem")
        val trashPath = storage.trashDirectory.resolve("$id.schem")
        assertFalse(Files.exists(schematicPath), "schematic must be gone from schematics/")
        assertTrue(Files.exists(trashPath), "schematic must now be in trash/")

        val deleteResult = storage.deleteFromTrash(id)
        assertEquals(SchematicStorage.TrashDeleteResult.Deleted, deleteResult)
        assertFalse(Files.exists(trashPath))

        // Deleting again is reported, not thrown.
        val secondDelete = storage.deleteFromTrash(id)
        assertEquals(SchematicStorage.TrashDeleteResult.NotFound, secondDelete)
    }

    @Test
    fun `moveToTrash reports SourceMissing without throwing when there is nothing to move`() {
        storage.initializeDirectories()

        val result = storage.moveToTrash(UUID.randomUUID())

        assertEquals(SchematicStorage.TrashMoveResult.SourceMissing, result)
    }

    @Test
    fun `cleanupTempDirectory removes leftover tmp files on startup`() {
        storage.initializeDirectories()
        val leftoverA = storage.tempDirectory.resolve("${UUID.randomUUID()}.schem.tmp")
        val leftoverB = storage.tempDirectory.resolve("${UUID.randomUUID()}.schem.tmp")
        Files.write(leftoverA, "abandoned".toByteArray())
        Files.write(leftoverB, "abandoned".toByteArray())
        val unrelatedFile = storage.tempDirectory.resolve("keep-me.txt")
        Files.write(unrelatedFile, "keep".toByteArray())

        val result = storage.cleanupTempDirectory()

        assertEquals(2, result.deletedCount)
        assertTrue(result.failedPaths.isEmpty())
        assertFalse(Files.exists(leftoverA))
        assertFalse(Files.exists(leftoverB))
        assertTrue(Files.exists(unrelatedFile), "cleanup must not touch unrelated files")
    }

    @Test
    fun `cleanupTempDirectory is a no-op when the tmp directory does not exist yet`() {
        // Deliberately do NOT call initializeDirectories().
        val result = storage.cleanupTempDirectory()

        assertEquals(0, result.deletedCount)
        assertTrue(result.failedPaths.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // cleanupTrashDirectory (SS13.4 step 6 - orphaned trash files left by an interrupted remove)
    // -------------------------------------------------------------------------------------

    @Test
    fun `cleanupTrashDirectory removes leftover trashed schem files on startup and reports the count`() {
        storage.initializeDirectories()
        val leftoverA = storage.trashDirectory.resolve("${UUID.randomUUID()}.schem")
        val leftoverB = storage.trashDirectory.resolve("${UUID.randomUUID()}.schem")
        Files.write(leftoverA, "abandoned".toByteArray())
        Files.write(leftoverB, "abandoned".toByteArray())
        val unrelatedFile = storage.trashDirectory.resolve("keep-me.txt")
        Files.write(unrelatedFile, "keep".toByteArray())

        val result = storage.cleanupTrashDirectory()

        assertEquals(2, result.deletedCount)
        assertTrue(result.failedPaths.isEmpty())
        assertFalse(Files.exists(leftoverA))
        assertFalse(Files.exists(leftoverB))
        assertTrue(Files.exists(unrelatedFile), "cleanup must not touch unrelated files")
    }

    @Test
    fun `cleanupTrashDirectory is a no-op when the trash directory does not exist yet`() {
        // Deliberately do NOT call initializeDirectories().
        val result = storage.cleanupTrashDirectory()

        assertEquals(0, result.deletedCount)
        assertTrue(result.failedPaths.isEmpty())
    }

    // -------------------------------------------------------------------------------------
    // toRealPath() containment (SS21-10) - a symlink planted inside schematics/ must not defeat
    // the path-containment check. Creating a symbolic link requires elevated privileges/Developer
    // Mode on Windows, so this test skips itself (rather than failing) wherever that is not
    // available, per the task instructions.
    // -------------------------------------------------------------------------------------

    @Test
    fun `resolveForReadPath rejects a symlink inside the schematics directory that escapes it`() {
        storage.initializeDirectories()

        val outsideDirectory = Files.createTempDirectory("schemdepot-outside")
        Files.write(outsideDirectory.resolve("secret.schem"), "top secret".toByteArray())

        val linkPath = storage.schematicsDirectory.resolve("escape-link")
        try {
            Files.createSymbolicLink(linkPath, outsideDirectory)
        } catch (e: IOException) {
            Assumptions.assumeTrue(
                false,
                "skipping: cannot create symbolic links in this environment (likely missing " +
                    "admin privileges / Developer Mode on Windows): ${e.message}",
            )
            return
        } catch (e: UnsupportedOperationException) {
            Assumptions.assumeTrue(false, "skipping: this filesystem does not support symbolic links: ${e.message}")
            return
        }

        val result = storage.resolveForReadPath(linkPath.resolve("secret.schem"))

        assertTrue(result is SchematicStorage.ReadResolution.Rejected, "expected Rejected but was $result")
    }
}
