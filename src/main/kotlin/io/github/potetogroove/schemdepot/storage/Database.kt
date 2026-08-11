package io.github.potetogroove.schemdepot.storage

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Provides SQLite connections for SchemDepot's registry database.
 *
 * Every connection returned by [openConnection] must be closed by the caller, normally via
 * `use {}` (project rule: SQLite connections require try-with-resources / `use {}`).
 *
 * Driver loading note: the `org.xerial:sqlite-jdbc` dependency is relocated by the shadowJar
 * task from `org.sqlite` to `io.github.potetogroove.schemdepot.libs.sqlite` (see
 * build.gradle.kts `relocate(...)` + `mergeServiceFiles()`). `mergeServiceFiles()` relocates the
 * `META-INF/services/java.sql.Driver` service file contents along with the classes, so the
 * JDBC 4 driver auto-discovery mechanism (`DriverManager.getConnection` scanning registered
 * `java.sql.Driver` services) finds the relocated driver without any code-level reference to its
 * class name. Do NOT hardcode `"org.sqlite.JDBC"` as a string (e.g. via `Class.forName(...)`) -
 * shadow's relocator rewrites bytecode class references, not arbitrary string literals, so a
 * hardcoded string would silently break after relocation.
 */
class Database(private val databaseFile: File) {

    private val jdbcUrl: String = "jdbc:sqlite:${databaseFile.absoluteFile}"

    /**
     * Opens a new SQLite connection with SchemDepot's required pragmas applied
     * (`foreign_keys = ON`, `journal_mode = WAL`).
     */
    fun openConnection(): Connection {
        val connection = DriverManager.getConnection(jdbcUrl)
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
        }
        return connection
    }
}
