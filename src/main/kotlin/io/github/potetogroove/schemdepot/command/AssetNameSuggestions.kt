package io.github.potetogroove.schemdepot.command

import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import io.github.potetogroove.schemdepot.asset.Asset
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Command-layer-owned, read-only-from-Brigadier's-perspective cache of registered asset names,
 * keyed by [io.github.potetogroove.schemdepot.asset.AssetName.normalize]d name
 * (docs/SchemDepot_DESIGN.md SS18 "maintain a lightweight in-memory asset-name index").
 *
 * ## Why this class exists instead of reading `AssetService`'s own index
 * `AssetService` already keeps an equivalent `ConcurrentHashMap<String, Asset>` internally (SS19),
 * but it is `private` and the Phase 6 task scope explicitly forbids modifying
 * [io.github.potetogroove.schemdepot.asset.AssetService] to expose it. This class is therefore an
 * independent, `command`-package-local index built entirely from data [AssetService] already
 * hands back through its **public** API:
 * - [SchemDepotCommand] performs one initial bulk load via [AssetService.list] when the command is
 *   registered (see [SchemDepotCommand.primeNameIndex]).
 * - [SchemDepotCommand] then calls [put]/[remove] itself from the `add`/`rename`/`remove` command
 *   handlers' callbacks, using the [Asset] each of those already returns on success - no further
 *   reads of `AssetService` internals are needed.
 *
 * This keeps [suggestionsStartingWith] a pure in-memory, no-I/O, [ConcurrentHashMap] lookup, so a
 * [SuggestionProvider] built from it can answer every keystroke synchronously without touching
 * SQLite (SS18: "Do not perform blocking SQLite queries synchronously for every keystroke").
 */
class AssetNameIndex {

    /** normalizedName -> current display name. */
    private val names = ConcurrentHashMap<String, String>()

    /** Replaces the entire cache contents (used once, by the initial bulk load). */
    fun replaceAll(assets: Collection<Asset>) {
        val next = HashMap<String, String>(assets.size)
        for (asset in assets) {
            next[asset.normalizedName] = asset.name
        }
        names.clear()
        names.putAll(next)
    }

    /** Inserts/updates a single entry (called after a successful `add`/`rename`). */
    fun put(asset: Asset) {
        names[asset.normalizedName] = asset.name
    }

    /** Removes a single entry (called after a successful `remove`, or the old name of a `rename`). */
    fun remove(normalizedName: String) {
        names.remove(normalizedName)
    }

    /** Current display name for [normalizedName], if cached. */
    fun get(normalizedName: String): String? = names[normalizedName]

    /**
     * Display names whose normalized form starts with [prefix] (already-normalized or not; this
     * normalizes [prefix] itself), sorted for stable tab-completion ordering.
     */
    fun suggestionsStartingWith(prefix: String): List<String> {
        val normalizedPrefix = prefix.lowercase(Locale.ROOT)
        return names.entries
            .asSequence()
            .filter { it.key.startsWith(normalizedPrefix) }
            .map { it.value }
            .sorted()
            .toList()
    }
}

/**
 * Builds the [SuggestionProvider] used by every asset-name argument node in
 * [SchemDepotCommand] (docs/SchemDepot_DESIGN.md SS18).
 */
object AssetNameSuggestions {

    fun forIndex(index: AssetNameIndex): SuggestionProvider<CommandSourceStack> =
        SuggestionProvider { _, builder ->
            for (name in index.suggestionsStartingWith(builder.remainingLowerCase)) {
                builder.suggest(name)
            }
            CompletableFuture.completedFuture<Suggestions>(builder.build())
        }
}
