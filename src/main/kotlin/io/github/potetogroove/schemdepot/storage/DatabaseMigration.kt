package io.github.potetogroove.schemdepot.storage

import java.sql.Connection

/**
 * Applies SQLite schema migrations tracked via `PRAGMA user_version` (docs/SchemDepot_DESIGN.md
 * SS8.2).
 */
object DatabaseMigration {

    private const val CURRENT_SCHEMA_VERSION = 1

    private const val CREATE_ASSETS_TABLE = """
        CREATE TABLE IF NOT EXISTS assets (
            id              TEXT PRIMARY KEY,
            name            TEXT NOT NULL,
            normalized_name TEXT NOT NULL UNIQUE,

            author_uuid     TEXT NOT NULL,
            author_name     TEXT NOT NULL,

            created_at      INTEGER NOT NULL,
            updated_at      INTEGER NOT NULL,

            size_x          INTEGER NOT NULL,
            size_y          INTEGER NOT NULL,
            size_z          INTEGER NOT NULL,

            schematic_file  TEXT NOT NULL UNIQUE
        )
    """

    private const val CREATE_AUTHOR_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_assets_author_uuid ON assets(author_uuid)"

    private const val CREATE_CREATED_AT_INDEX =
        "CREATE INDEX IF NOT EXISTS idx_assets_created_at ON assets(created_at)"

    /**
     * Migrates the schema reachable via [connection] up to [CURRENT_SCHEMA_VERSION].
     *
     * Idempotent: DDL statements use `IF NOT EXISTS`, and the whole migration runs inside a
     * single transaction that only advances `user_version` on success, so re-running this
     * (against an already-migrated database, or after a previous successful run) is a safe
     * no-op once `user_version` already reflects the current schema version.
     */
    fun migrate(connection: Connection) {
        val currentVersion = readUserVersion(connection)
        if (currentVersion >= CURRENT_SCHEMA_VERSION) {
            return
        }

        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            if (currentVersion < 1) {
                applyV1(connection)
            }
            writeUserVersion(connection, CURRENT_SCHEMA_VERSION)
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun applyV1(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(CREATE_ASSETS_TABLE)
            statement.execute(CREATE_AUTHOR_INDEX)
            statement.execute(CREATE_CREATED_AT_INDEX)
        }
    }

    private fun readUserVersion(connection: Connection): Int {
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { resultSet ->
                return if (resultSet.next()) resultSet.getInt(1) else 0
            }
        }
    }

    private fun writeUserVersion(connection: Connection, version: Int) {
        // PRAGMA statements do not support JDBC bind parameters. `version` is an internal
        // integer constant (CURRENT_SCHEMA_VERSION), never user input, so interpolating it here
        // does not violate the "no unchecked user input in SQL" rule (SS21-2/21-3).
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA user_version = $version")
        }
    }
}
