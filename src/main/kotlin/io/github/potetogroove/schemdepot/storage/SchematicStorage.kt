package io.github.potetogroove.schemdepot.storage

import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Pure `java.nio.file` based storage for SchemDepot's backing `.schem` files.
 *
 * This class intentionally has **no** dependency on the Bukkit API or the WorldEdit/FAWE API so
 * that it can be unit-tested without a running server (see docs/SchemDepot_DESIGN.md SS27.3).
 * WorldEdit clipboard read/write is Phase 4's (`worldedit/`) responsibility; this class only
 * knows how to move bytes to/from the correct place on disk.
 *
 * ## Threading
 * Every method here performs **blocking** file I/O. Callers (the Phase 5 application service
 * layer) MUST invoke this class only from a worker thread, never from the Bukkit main thread
 * (see docs/SchemDepot_DESIGN.md SS12). This class does not do any scheduling itself.
 *
 * ## Path safety (SS21-1, SS21-10, SS30-5, SS30-18)
 * Every on-disk file name is derived exclusively from an asset [UUID] (`<uuid>.schem`). A
 * human-supplied asset display name is never used to build a filesystem path. Read access is
 * additionally re-validated (normalized path containment check) against the schematics directory
 * root before a caller is allowed to open the resolved path, as defense-in-depth against path
 * traversal.
 *
 * ## Logging (SS21-8)
 * Some log messages produced by this class include absolute server filesystem paths for
 * diagnostic purposes (server log only). Callers MUST NOT forward this raw log/exception text to
 * players; player-facing messages must be built independently from the sealed result types
 * returned by this class (see docs/SchemDepot_DESIGN.md SS17 / SS21-8).
 */
class SchematicStorage(
    dataDirectory: Path,
    schematicsDirectoryName: String = "schematics",
    tempDirectoryName: String = "tmp",
    trashDirectoryName: String = "trash",
    private val logger: Logger = Logger.getLogger(SchematicStorage::class.java.name),
) {

    /** Directory that holds the live, registered `.schem` files. */
    val schematicsDirectory: Path =
        dataDirectory.resolve(schematicsDirectoryName).toAbsolutePath().normalize()

    /** Directory used for staging in-progress writes before an atomic move (SS13.1). */
    val tempDirectory: Path =
        dataDirectory.resolve(tempDirectoryName).toAbsolutePath().normalize()

    /** Directory used to stage removed `.schem` files before final deletion (SS13.4). */
    val trashDirectory: Path =
        dataDirectory.resolve(trashDirectoryName).toAbsolutePath().normalize()

    /**
     * Creates [schematicsDirectory], [tempDirectory], and [trashDirectory] if they do not
     * already exist. Idempotent; safe to call every startup.
     */
    fun initializeDirectories() {
        Files.createDirectories(schematicsDirectory)
        Files.createDirectories(tempDirectory)
        Files.createDirectories(trashDirectory)
    }

    // ---------------------------------------------------------------------
    // Write (SS13.1 steps 8-10, SS13.2)
    // ---------------------------------------------------------------------

    /** Outcome of [write]. */
    sealed interface WriteResult {
        /** The schematic was written and moved into [schematicsDirectory]. */
        data class Success(val path: Path) : WriteResult

        /** A schematic already exists for this [id]; the write was rejected (SS30-15/SS30-19). */
        data class AlreadyExists(val id: UUID) : WriteResult

        /** Writing or moving the file failed. [cause] is safe to log but not to show players. */
        data class Failed(val id: UUID, val cause: Exception) : WriteResult
    }

    /**
     * Writes a schematic for [id] via a temp-file-then-atomic-move sequence
     * (docs/SchemDepot_DESIGN.md SS13.1/SS13.2):
     *
     * 1. Opens `tmp/<id>.schem.tmp` and invokes [writer] with the resulting output stream. The
     *    caller is responsible for writing the schematic contents (e.g. via a WorldEdit
     *    `ClipboardWriter`); the stream is closed by this method when [writer] returns or throws.
     * 2. Moves the completed temp file into `schematics/<id>.schem`, preferring
     *    [StandardCopyOption.ATOMIC_MOVE] and falling back to a plain move only when
     *    [AtomicMoveNotSupportedException] is thrown (e.g. temp and target on different
     *    filesystems).
     * 3. Never silently overwrites an existing schematic for the same [id] (SS30-15/SS30-19): if
     *    the destination already exists, [WriteResult.AlreadyExists] is returned and the temp
     *    file is discarded.
     *
     * If [writer] throws, or the move fails for any reason, the temp file is deleted
     * (best-effort) and no partial/incorrect state is left behind in [schematicsDirectory].
     */
    fun write(id: UUID, writer: (OutputStream) -> Unit): WriteResult {
        val tempPath = tempPathFor(id)
        val targetPath = schematicPathFor(id)

        try {
            Files.newOutputStream(
                tempPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { output -> writer(output) }
        } catch (e: Exception) {
            deleteQuietly(tempPath, "temp schematic file after failed write")
            return WriteResult.Failed(id, e)
        }

        if (Files.exists(targetPath)) {
            deleteQuietly(tempPath, "temp schematic file after duplicate-target rejection")
            return WriteResult.AlreadyExists(id)
        }

        return try {
            moveIntoPlace(tempPath, targetPath)
            WriteResult.Success(targetPath)
        } catch (e: FileAlreadyExistsException) {
            // Race: something created the target between our check above and the move attempt.
            deleteQuietly(tempPath, "temp schematic file after duplicate-target rejection")
            WriteResult.AlreadyExists(id)
        } catch (e: Exception) {
            deleteQuietly(tempPath, "temp schematic file after failed move")
            WriteResult.Failed(id, e)
        }
    }

    private fun moveIntoPlace(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---------------------------------------------------------------------
    // Read
    // ---------------------------------------------------------------------

    /** Outcome of resolving a schematic file for reading. */
    sealed interface ReadResolution {
        /** The schematic file exists and [path] is safely contained in [schematicsDirectory]. */
        data class Found(val path: Path) : ReadResolution

        /** No schematic file exists for the requested id. Not an error path (AC-11). */
        data object NotFound : ReadResolution

        /** The resolved path escaped [schematicsDirectory]; the read was rejected (SS21-10). */
        data object Rejected : ReadResolution
    }

    /**
     * Resolves the on-disk path of the schematic identified by [id] for reading.
     *
     * Never throws for a missing file; returns [ReadResolution.NotFound] instead so callers can
     * report a clean, non-crashing error to the player (AC-11). Callers are expected to open the
     * returned [Path] themselves (e.g. via WorldEdit's clipboard format detection/reader).
     */
    fun resolveForRead(id: UUID): ReadResolution = resolveForReadPath(schematicPathFor(id))

    /**
     * Path-accepting variant of [resolveForRead]. Exposed as `internal` purely so unit tests can
     * exercise the path-containment check with a path that was not derived from a [UUID] (see
     * docs/SchemDepot_DESIGN.md SS27.3, "path containment"). Production callers should always go
     * through [resolveForRead], since on-disk paths must only ever be derived from a UUID
     * (SS21-1/SS30-5).
     */
    internal fun resolveForReadPath(path: Path): ReadResolution {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(schematicsDirectory)) {
            return ReadResolution.Rejected
        }
        return if (Files.isRegularFile(normalized)) {
            ReadResolution.Found(normalized)
        } else {
            ReadResolution.NotFound
        }
    }

    // ---------------------------------------------------------------------
    // Delete / trash lifecycle (SS13.4)
    // ---------------------------------------------------------------------

    /** Outcome of [moveToTrash]. */
    sealed interface TrashMoveResult {
        data class Moved(val trashPath: Path) : TrashMoveResult

        /** The live schematic was already absent; nothing to move. Not treated as fatal. */
        data object SourceMissing : TrashMoveResult

        /** Moving failed. [cause] is safe to log but not to show players. */
        data class Failed(val cause: Exception) : TrashMoveResult
    }

    /**
     * Moves `schematics/<id>.schem` to `trash/<id>.schem`.
     *
     * Intended to be called **before** the registry row is deleted (SS13.4 step 3), so a crash
     * between the file move and the DB delete leaves the asset merely "unpasteable" rather than
     * destroying data. Failure here (other than the source simply being absent) MUST NOT be
     * treated as fatal to the overall remove operation by the caller (SS13.4 step 6: log, don't
     * corrupt other entries) - it is reported as a result value instead of an exception so the
     * caller can decide.
     */
    fun moveToTrash(id: UUID): TrashMoveResult {
        val source = schematicPathFor(id)
        val target = trashPathFor(id)

        if (!Files.isRegularFile(source)) {
            return TrashMoveResult.SourceMissing
        }

        return try {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: FileAlreadyExistsException) {
                // A stale trash entry for the same id can only happen if a previous removal did
                // not finish cleanly; replacing it keeps the remove sequence idempotent/retryable.
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
            }
            TrashMoveResult.Moved(target)
        } catch (e: Exception) {
            TrashMoveResult.Failed(e)
        }
    }

    /** Outcome of [deleteFromTrash]. */
    sealed interface TrashDeleteResult {
        data object Deleted : TrashDeleteResult
        data object NotFound : TrashDeleteResult

        /** Deletion failed. [cause] is safe to log but not to show players. */
        data class Failed(val cause: Exception) : TrashDeleteResult
    }

    /**
     * Permanently deletes `trash/<id>.schem`.
     *
     * Intended to be called **after** the registry row deletion has committed successfully
     * (SS13.4 step 5). Never throws; failures are reported as [TrashDeleteResult.Failed] so the
     * caller can log an orphan-cleanup warning (SS13.4 step 6) without crashing the remove flow.
     */
    fun deleteFromTrash(id: UUID): TrashDeleteResult {
        val target = trashPathFor(id)
        return try {
            if (Files.deleteIfExists(target)) {
                TrashDeleteResult.Deleted
            } else {
                TrashDeleteResult.NotFound
            }
        } catch (e: IOException) {
            TrashDeleteResult.Failed(e)
        }
    }

    // ---------------------------------------------------------------------
    // Startup cleanup
    // ---------------------------------------------------------------------

    /** Result of [cleanupTempDirectory]. */
    data class TempCleanupResult(val deletedCount: Int, val failedPaths: List<Path>)

    /**
     * Deletes any leftover `*.tmp` files in [tempDirectory], e.g. left behind by a write that was
     * interrupted by a crash. Intended to be called once during plugin startup, before any
     * command handling begins.
     */
    fun cleanupTempDirectory(): TempCleanupResult {
        if (!Files.isDirectory(tempDirectory)) {
            return TempCleanupResult(0, emptyList())
        }

        var deletedCount = 0
        val failedPaths = mutableListOf<Path>()

        Files.newDirectoryStream(tempDirectory, "*.tmp").use { stream ->
            for (path in stream) {
                try {
                    Files.delete(path)
                    deletedCount++
                } catch (e: IOException) {
                    logger.log(Level.WARNING, "Failed to clean up leftover temp file: $path", e)
                    failedPaths.add(path)
                }
            }
        }

        return TempCleanupResult(deletedCount, failedPaths)
    }

    // ---------------------------------------------------------------------
    // Path helpers (UUID-only, SS21-1/SS30-5) - never derived from a display name.
    // ---------------------------------------------------------------------

    private fun fileNameFor(id: UUID): String = "$id.schem"

    private fun tempFileNameFor(id: UUID): String = "$id.schem.tmp"

    internal fun schematicPathFor(id: UUID): Path = schematicsDirectory.resolve(fileNameFor(id))

    private fun tempPathFor(id: UUID): Path = tempDirectory.resolve(tempFileNameFor(id))

    private fun trashPathFor(id: UUID): Path = trashDirectory.resolve(fileNameFor(id))

    private fun deleteQuietly(path: Path, description: String) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            // Server-log only; this message includes an absolute path and must not be forwarded
            // to players (SS21-8).
            logger.log(Level.WARNING, "Failed to delete $description: $path", e)
        }
    }
}
