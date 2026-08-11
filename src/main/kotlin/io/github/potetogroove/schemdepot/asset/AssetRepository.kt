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
    fun updateName(id: UUID, name: String, normalizedName: String, updatedAt: Instant)
    fun delete(id: UUID)
}
