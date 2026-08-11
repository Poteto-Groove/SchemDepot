package io.github.potetogroove.schemdepot.asset

import java.util.Locale

/**
 * Single authoritative source for asset-name validation, normalization, and reserved-word
 * checking (docs/SchemDepot_DESIGN.md SS6.2 "Reserved asset names", SS6.3 "Asset name
 * validation", SS21-5 "Normalize names with Locale.ROOT", SS21-6 "Reject path separators and
 * unsupported characters via the name regex").
 *
 * All other code (commands, AssetService, repository) MUST route name validation and
 * normalization through this object rather than re-implementing the regex or reserved-word set.
 */
object AssetName {

    /**
     * SS6.3 recommended validation regex: 1-64 characters, must start with an ASCII letter or
     * digit, followed by up to 63 more ASCII letters/digits/`.`/`_`/`-`.
     *
     * Path separators (`/`, `\`), `..` traversal sequences, spaces, and any other symbol are
     * rejected simply because they do not match this pattern (SS21-6) - no separate
     * path-traversal check is needed here.
     */
    private val VALID_NAME_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

    /**
     * SS6.2 reserved command-shortcut words, kept centralized in this single constant.
     * Comparison against candidate names is case-insensitive; entries are stored lowercase and
     * compared against the normalized candidate.
     */
    private val RESERVED_NAMES: Set<String> = setOf(
        "help",
        "add",
        "paste",
        "list",
        "info",
        "rename",
        "remove",
        "reload",
        "version",
        "admin",
    )

    /**
     * Canonical lookup-key normalization (SS6.3 "Canonical lookup key", SS21-5).
     */
    fun normalize(name: String): String = name.lowercase(Locale.ROOT)

    /**
     * Validates [name] for format and reserved-word rules, returning a sealed result rather
     * than throwing (SS31: avoid exception-driven control flow at this boundary).
     */
    fun validate(name: String): AssetNameResult {
        if (!VALID_NAME_PATTERN.matches(name)) {
            return AssetNameResult.InvalidFormat(name)
        }

        val normalizedName = normalize(name)
        if (normalizedName in RESERVED_NAMES) {
            return AssetNameResult.Reserved(name)
        }

        return AssetNameResult.Valid(name, normalizedName)
    }
}

/**
 * Result of [AssetName.validate]. Sealed so callers must handle every case explicitly instead
 * of relying on thrown exceptions.
 */
sealed interface AssetNameResult {
    data class Valid(val name: String, val normalizedName: String) : AssetNameResult
    data class InvalidFormat(val name: String) : AssetNameResult
    data class Reserved(val name: String) : AssetNameResult
}
