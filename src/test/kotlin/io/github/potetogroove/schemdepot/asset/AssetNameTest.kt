package io.github.potetogroove.schemdepot.asset

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AssetNameTest {

    @ParameterizedTest
    @ValueSource(strings = ["OakTree", "oak_tree", "city.lamp-01", "House2", "a", "Z9"])
    fun `valid names are accepted`(name: String) {
        val result = AssetName.validate(name)
        assertTrue(result is AssetNameResult.Valid)
    }

    @Test
    fun `a name exactly 64 characters long is accepted`() {
        val name = "a" + "b".repeat(63)
        assertEquals(64, name.length)

        val result = AssetName.validate(name)

        assertTrue(result is AssetNameResult.Valid)
    }

    @Test
    fun `empty name is rejected as invalid format`() {
        val result = AssetName.validate("")
        assertTrue(result is AssetNameResult.InvalidFormat)
    }

    @Test
    fun `a name over 64 characters is rejected`() {
        val name = "a" + "b".repeat(64)
        assertEquals(65, name.length)

        val result = AssetName.validate(name)

        assertTrue(result is AssetNameResult.InvalidFormat)
    }

    @ParameterizedTest
    @ValueSource(strings = [".starts-with-dot", "-starts-with-dash", "_starts-with-underscore", "Oak Tree", "tree*"])
    fun `names starting with a symbol or containing disallowed characters are rejected`(name: String) {
        val result = AssetName.validate(name)
        assertTrue(result is AssetNameResult.InvalidFormat)
    }

    @ParameterizedTest
    @ValueSource(strings = ["../tree", "/tree", "a/b", "a\\b"])
    fun `path separators and traversal sequences are rejected`(name: String) {
        val result = AssetName.validate(name)
        assertTrue(result is AssetNameResult.InvalidFormat)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "list", "LIST", "List",
            "add", "Add",
            "paste", "PASTE",
            "info", "Info",
            "rename", "Rename",
            "remove", "Remove",
            "reload", "Reload",
            "version", "Version",
            "admin", "Admin",
            "help", "Help",
        ],
    )
    fun `reserved names are rejected regardless of case`(name: String) {
        val result = AssetName.validate(name)
        assertTrue(result is AssetNameResult.Reserved)
    }

    @Test
    fun `normalize lowercases using Locale ROOT`() {
        assertEquals("oaktree", AssetName.normalize("OakTree"))
    }

    @Test
    fun `Tree and tree normalize to the same lookup key`() {
        assertEquals(AssetName.normalize("Tree"), AssetName.normalize("tree"))
    }
}
