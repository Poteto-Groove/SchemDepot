package io.github.potetogroove.schemdepot.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.logging.Logger

/**
 * Unit tests for [SchemDepotConfig]'s path validation (SS16, "Invalid values should be
 * normalized ... rather than silent undefined behavior").
 *
 * [SchemDepotConfig.load] itself takes a Bukkit `org.bukkit.configuration.file.FileConfiguration`
 * (e.g. `YamlConfiguration`), which is part of `paper-api`. `paper-api` is only `compileOnly` for
 * the main source set and is **not** on this project's test compile/runtime classpath - confirmed
 * via `gradlew dependencies --configuration testRuntimeClasspath`, which resolves
 * `worldedit-bukkit`'s own transitive dependencies but pulls in no `io.papermc`/`org.bukkit` API
 * jar at all. Adding one was out of scope for this task ("パスが無い場合は勝手に依存を足さず報告す
 * ること"), so this suite instead exercises [SchemDepotConfig.requireNonBlankPath] directly - the
 * `internal` helper [SchemDepotConfig.load] delegates every `storage.*` value to, which its own
 * KDoc already documents as existing specifically for this kind of Bukkit-free unit test
 * (SS27.1).
 */
class SchemDepotConfigTest {

    private val logger: Logger = Logger.getLogger(SchemDepotConfigTest::class.java.name)

    private fun validate(value: String?, default: String = "schematics"): String =
        SchemDepotConfig.requireNonBlankPath(value, default, "storage.schematics-directory", logger)

    @Test
    fun `a plain single-component value passes through unchanged`() {
        assertEquals("schematics", validate("schematics"))
        assertEquals("my-schematics_dir.2", validate("my-schematics_dir.2"))
    }

    @Test
    fun `an absolute path falls back to the default`() {
        assertEquals("schematics", validate("/etc/passwd"))
        assertEquals("schematics", validate("C:\\Windows\\System32"))
    }

    @Test
    fun `a traversal element falls back to the default`() {
        assertEquals("schematics", validate(".."))
        assertEquals("schematics", validate("../../world/region"))
    }

    @Test
    fun `the current-directory element falls back to the default`() {
        assertEquals("schematics", validate("."))
    }

    @Test
    fun `a value containing a forward slash separator falls back to the default`() {
        assertEquals("schematics", validate("foo/bar"))
    }

    @Test
    fun `a value containing a backslash separator falls back to the default`() {
        assertEquals("schematics", validate("foo\\bar"))
    }

    @Test
    fun `a null or blank value falls back to the default`() {
        assertEquals("schematics", validate(null))
        assertEquals("schematics", validate(""))
        assertEquals("schematics", validate("   "))
    }
}
