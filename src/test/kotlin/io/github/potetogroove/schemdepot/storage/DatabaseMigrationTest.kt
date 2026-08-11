package io.github.potetogroove.schemdepot.storage

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DatabaseMigrationTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `migrate creates the assets table and advances user_version to 1`() {
        val database = Database(File(tempDir, "assets.db"))

        database.openConnection().use { connection ->
            DatabaseMigration.migrate(connection)

            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA user_version").use { resultSet ->
                    resultSet.next()
                    assertEquals(1, resultSet.getInt(1))
                }
            }

            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'assets'",
                ).use { resultSet ->
                    assertTrue(resultSet.next())
                }
            }
        }
    }

    @Test
    fun `migrate is idempotent when run twice on the same connection`() {
        val database = Database(File(tempDir, "assets2.db"))

        database.openConnection().use { connection ->
            assertDoesNotThrow { DatabaseMigration.migrate(connection) }
            assertDoesNotThrow { DatabaseMigration.migrate(connection) }
        }
    }

    @Test
    fun `migrate is idempotent across separate connections to the same database file`() {
        val dbFile = File(tempDir, "assets3.db")
        val database = Database(dbFile)

        database.openConnection().use { connection -> DatabaseMigration.migrate(connection) }

        assertDoesNotThrow {
            database.openConnection().use { connection -> DatabaseMigration.migrate(connection) }
        }
    }
}
