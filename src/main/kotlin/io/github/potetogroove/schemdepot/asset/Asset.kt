package io.github.potetogroove.schemdepot.asset

import java.time.Instant
import java.util.UUID

/**
 * Immutable domain representation of a registered SchemDepot asset.
 *
 * Field names, types, and order mirror docs/SchemDepot_DESIGN.md SS8.3 exactly (the Java
 * `record Asset` there). This type must not leak database/storage concerns (e.g. JDBC types) -
 * see SS8.3 "Do not expose database concerns from the domain object."
 */
data class Asset(
    val id: UUID,
    val name: String,
    val normalizedName: String,
    val authorUuid: UUID,
    val authorName: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val schematicFile: String,
)
