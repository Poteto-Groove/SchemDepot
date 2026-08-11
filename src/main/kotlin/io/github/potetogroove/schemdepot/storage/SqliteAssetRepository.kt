package io.github.potetogroove.schemdepot.storage

import io.github.potetogroove.schemdepot.asset.Asset
import io.github.potetogroove.schemdepot.asset.AssetRepository
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * SQLite-backed [AssetRepository] implementation.
 *
 * All SQL uses [java.sql.PreparedStatement] exclusively (docs/SchemDepot_DESIGN.md SS21-2/21-3,
 * SS30 rules 6/7) - no user-controlled value is ever concatenated into a SQL string. Every
 * `Connection`/`PreparedStatement`/`ResultSet` is closed via `use {}`.
 */
class SqliteAssetRepository(private val database: Database) : AssetRepository {

    override fun findByName(normalizedName: String): Asset? {
        database.openConnection().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM assets WHERE normalized_name = ?",
            ).use { statement ->
                statement.setString(1, normalizedName)
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) resultSet.toAsset() else null
                }
            }
        }
    }

    override fun findById(id: UUID): Asset? {
        database.openConnection().use { connection ->
            connection.prepareStatement(
                "SELECT * FROM assets WHERE id = ?",
            ).use { statement ->
                statement.setString(1, id.toString())
                statement.executeQuery().use { resultSet ->
                    return if (resultSet.next()) resultSet.toAsset() else null
                }
            }
        }
    }

    override fun list(limit: Int, offset: Int): List<Asset> {
        database.openConnection().use { connection ->
            // Ordered by created_at ascending (with id as a stable tiebreaker for equal
            // timestamps): this matches idx_assets_created_at (SS8.2/SS22 - "Database queries
            // use indexes for name/author/date where relevant") and gives deterministic,
            // append-stable pagination where earlier-registered assets keep a stable page
            // position as new assets are added.
            connection.prepareStatement(
                "SELECT * FROM assets ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?",
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { resultSet ->
                    val assets = mutableListOf<Asset>()
                    while (resultSet.next()) {
                        assets.add(resultSet.toAsset())
                    }
                    return assets
                }
            }
        }
    }

    override fun count(): Long {
        database.openConnection().use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM assets").use { statement ->
                statement.executeQuery().use { resultSet ->
                    resultSet.next()
                    return resultSet.getLong(1)
                }
            }
        }
    }

    override fun existsByName(normalizedName: String): Boolean {
        database.openConnection().use { connection ->
            connection.prepareStatement(
                "SELECT 1 FROM assets WHERE normalized_name = ? LIMIT 1",
            ).use { statement ->
                statement.setString(1, normalizedName)
                statement.executeQuery().use { resultSet ->
                    return resultSet.next()
                }
            }
        }
    }

    override fun insert(asset: Asset) {
        database.openConnection().use { connection ->
            withTransaction(connection) {
                connection.prepareStatement(
                    """
                    INSERT INTO assets (
                        id, name, normalized_name,
                        author_uuid, author_name,
                        created_at, updated_at,
                        size_x, size_y, size_z,
                        schematic_file
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, asset.id.toString())
                    statement.setString(2, asset.name)
                    statement.setString(3, asset.normalizedName)
                    statement.setString(4, asset.authorUuid.toString())
                    statement.setString(5, asset.authorName)
                    statement.setLong(6, asset.createdAt.toEpochMilli())
                    statement.setLong(7, asset.updatedAt.toEpochMilli())
                    statement.setInt(8, asset.sizeX)
                    statement.setInt(9, asset.sizeY)
                    statement.setInt(10, asset.sizeZ)
                    statement.setString(11, asset.schematicFile)
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun updateName(id: UUID, name: String, normalizedName: String, updatedAt: Instant) {
        database.openConnection().use { connection ->
            withTransaction(connection) {
                connection.prepareStatement(
                    "UPDATE assets SET name = ?, normalized_name = ?, updated_at = ? WHERE id = ?",
                ).use { statement ->
                    statement.setString(1, name)
                    statement.setString(2, normalizedName)
                    statement.setLong(3, updatedAt.toEpochMilli())
                    statement.setString(4, id.toString())
                    statement.executeUpdate()
                }
            }
        }
    }

    override fun delete(id: UUID) {
        database.openConnection().use { connection ->
            withTransaction(connection) {
                connection.prepareStatement("DELETE FROM assets WHERE id = ?").use { statement ->
                    statement.setString(1, id.toString())
                    statement.executeUpdate()
                }
            }
        }
    }

    /**
     * Runs [block] inside an explicit SQLite transaction: disables autocommit, commits on
     * success, rolls back on failure, and always restores the connection's original autocommit
     * state.
     */
    private fun withTransaction(connection: Connection, block: () -> Unit) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun ResultSet.toAsset(): Asset = Asset(
        id = UUID.fromString(getString("id")),
        name = getString("name"),
        normalizedName = getString("normalized_name"),
        authorUuid = UUID.fromString(getString("author_uuid")),
        authorName = getString("author_name"),
        createdAt = Instant.ofEpochMilli(getLong("created_at")),
        updatedAt = Instant.ofEpochMilli(getLong("updated_at")),
        sizeX = getInt("size_x"),
        sizeY = getInt("size_y"),
        sizeZ = getInt("size_z"),
        schematicFile = getString("schematic_file"),
    )
}
