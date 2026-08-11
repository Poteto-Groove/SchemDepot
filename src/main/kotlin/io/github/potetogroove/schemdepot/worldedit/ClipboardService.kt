package io.github.potetogroove.schemdepot.worldedit

import com.sk89q.worldedit.EmptyClipboardException
import com.sk89q.worldedit.WorldEditException
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import org.bukkit.entity.Player
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Read side of the WorldEdit clipboard integration: resolves the clipboard a player currently
 * holds and serializes it to a Sponge Schematic stream (design doc SS9, SS10.1, SS10.2, SS13.1).
 *
 * ## Ownership rule (SS10.1, SS30-4)
 * A [Clipboard] returned by [currentClipboardOf] belongs to the player's
 * [com.sk89q.worldedit.LocalSession]. SchemDepot MUST NOT close, mutate, or replace it. Nothing in
 * this class closes a clipboard, and [com.sk89q.worldedit.LocalSession.setClipboard] is never
 * called anywhere in SchemDepot.
 *
 * ## Errors are results, not exceptions (SS31, SS30-21)
 * "The player has no clipboard" is an expected outcome, not an error, so the WorldEdit
 * [EmptyClipboardException] is caught here and translated into [ClipboardResult.Empty]. Genuinely
 * unexpected failures become [ClipboardResult.Failed] and are always logged with context before
 * being returned - they are never swallowed silently.
 *
 * ## Testing
 * There is no unit test for this class: every method needs a live WorldEdit/FAWE platform and a
 * real `LocalSession`, neither of which can be created outside a running server. Behaviour is
 * verified in the Phase 7 integration pass (design doc SS29 Phase 7, AC-01/AC-02).
 */
class ClipboardService(
    private val facade: WorldEditFacade,
    private val logger: Logger = Logger.getLogger(ClipboardService::class.java.name),
) {

    /** Outcome of resolving a player's current WorldEdit clipboard. */
    sealed interface ClipboardResult {

        /**
         * The player holds a clipboard. [clipboard] is owned by the player's `LocalSession` and
         * must not be closed or mutated by SchemDepot (SS30-4).
         */
        data class Available(val clipboard: Clipboard) : ClipboardResult

        /** The player has no clipboard yet (has not run `//copy`/`//cut`). Expected path, AC-02. */
        data object Empty : ClipboardResult

        /**
         * WorldEdit failed unexpectedly while resolving the clipboard. Already logged with
         * context; [cause] is safe to log but must not be shown verbatim to players (SS21-8).
         */
        data class Failed(val cause: Exception) : ClipboardResult
    }

    /** Block dimensions of a clipboard, in WorldEdit's X/Y/Z order (SS8.3 `size_x/y/z`). */
    data class ClipboardDimensions(val sizeX: Int, val sizeY: Int, val sizeZ: Int) {
        /** Bounding-box block count; useful for the `limits.max-volume` check of SS16. */
        val volume: Long get() = sizeX.toLong() * sizeY.toLong() * sizeZ.toLong()
    }

    /**
     * Resolves the clipboard currently held by [player]'s WorldEdit session (SS10.1, SS13.1 step 3).
     *
     * **Main thread only** - this reads live WorldEdit session state through the Bukkit player
     * object (SS12, AC-12). Call it from the command handler before dispatching the heavy work to
     * the worker executor.
     */
    fun currentClipboardOf(player: Player): ClipboardResult {
        return try {
            val session = facade.localSessionOf(player)
            // Verified: LocalSession#getClipboard() throws EmptyClipboardException and returns a
            // ClipboardHolder; ClipboardHolder#getClipboard() returns the Clipboard.
            ClipboardResult.Available(session.clipboard.clipboard)
        } catch (empty: EmptyClipboardException) {
            // Expected control flow, not a failure: do not log at warning level, and do not
            // propagate the exception past this boundary (SS31).
            logger.log(
                Level.FINE,
                "Player ${player.uniqueId} has no WorldEdit clipboard.",
                empty,
            )
            ClipboardResult.Empty
        } catch (e: WorldEditException) {
            logger.log(
                Level.WARNING,
                "WorldEdit failed to provide a clipboard for player ${player.uniqueId}.",
                e,
            )
            ClipboardResult.Failed(e)
        } catch (e: RuntimeException) {
            logger.log(
                Level.WARNING,
                "Unexpected failure while reading the WorldEdit clipboard of player ${player.uniqueId}.",
                e,
            )
            ClipboardResult.Failed(e)
        }
    }

    /**
     * Returns the block dimensions of [clipboard] (SS13.1 step 5).
     *
     * Verified: `Clipboard#getDimensions(): BlockVector3` exists on WorldEdit 7.4.5, so no
     * `getRegion()`-based fallback is needed.
     *
     * Pure in-memory arithmetic on an already-resolved clipboard; safe on any thread.
     */
    fun dimensionsOf(clipboard: Clipboard): ClipboardDimensions {
        val dimensions = clipboard.dimensions
        return ClipboardDimensions(
            sizeX = dimensions.x(),
            sizeY = dimensions.y(),
            sizeZ = dimensions.z(),
        )
    }

    /**
     * Writes [clipboard] to [output] in the Sponge Schematic v3 format (SS9, SS13.1 step 8).
     *
     * The [com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter] is always closed via `use`.
     * The [clipboard] itself is never closed - when it came from a player's `LocalSession` its
     * lifetime belongs to WorldEdit (SS30-4).
     *
     * Note on the format constant: the design doc's pseudocode names
     * `BuiltInClipboardFormat.SPONGE_SCHEMATIC`, but on WorldEdit 7.4.5 that field is
     * `@Deprecated` and is merely an alias for `SPONGE_V2_SCHEMATIC` (confirmed by disassembling
     * the enum's static initializer). The current v3 format is therefore named explicitly.
     *
     * **Worker thread** - this performs blocking I/O and must not run on the main server thread
     * for large schematics (SS12, AC-12). It touches no Bukkit API. It does read the clipboard's
     * block data, so the caller must guarantee the clipboard is not being mutated concurrently;
     * for `/sd add` that holds, because the player's clipboard is only changed by their own
     * main-thread WorldEdit commands.
     *
     * @throws IOException if the schematic could not be serialized or written. Callers such as
     *   `SchematicStorage.write` translate this into their own result type; it is deliberately not
     *   caught here so a failed write can never be reported as success (SS30-19).
     */
    @Throws(IOException::class)
    fun writeTo(clipboard: Clipboard, output: OutputStream) {
        val buffered = BufferedOutputStream(output)
        // Verified: ClipboardFormat#getWriter(OutputStream): ClipboardWriter throws IOException,
        // and ClipboardWriter extends java.io.Closeable.
        BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC.getWriter(buffered).use { writer ->
            writer.write(clipboard)
        }
    }
}
