package io.github.potetogroove.schemdepot.asset

import java.time.Instant
import java.util.UUID

/**
 * Persistence boundary for [Asset] records (docs/SchemDepot_DESIGN.md SS15.3).
 *
 * Method names and argument lists mirror the design doc's Java interface exactly, with
 * `Optional<T>` translated to Kotlin's nullable `T?`.
 */
interface AssetRepository {
    fun findByName(normalizedName: String): Asset?
    fun findById(id: UUID): Asset?

    fun list(limit: Int, offset: Int): List<Asset>
    fun count(): Long

    fun existsByName(normalizedName: String): Boolean

    fun insert(asset: Asset)

    /**
     * Updates the display/normalized name of the row identified by [id].
     *
     * @return the number of rows actually updated: `1` on success, `0` when no row with [id]
     *   exists (any other value would mean the primary key is not unique). Callers MUST check
     *   this: silently treating a 0-row update as success lets an entry that no longer exists in
     *   the database - the single source of truth (SS19/SS36 invariant 4) - survive in the
     *   in-memory index as a ghost until the next restart.
     */
    fun updateName(id: UUID, name: String, normalizedName: String, updatedAt: Instant): Int

    /**
     * Deletes the row identified by [id].
     *
     * @return the number of rows actually deleted: `1` on success, `0` when no row with [id]
     *   exists. Callers MUST check this, for the same reason as [updateName].
     */
    fun delete(id: UUID): Int
}
