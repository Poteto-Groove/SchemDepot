package io.github.potetogroove.schemdepot.command

import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.suggestion.Suggestions
import io.github.potetogroove.schemdepot.asset.AssetService
import io.papermc.paper.command.brigadier.CommandSourceStack
import java.util.concurrent.CompletableFuture

/**
 * Builds the [SuggestionProvider] used by every asset-name argument node in
 * [SchemDepotCommand] (docs/SchemDepot_DESIGN.md SS18 "Tab Completion").
 *
 * Backed directly by [AssetService.suggestNames], which is documented as safe to call
 * synchronously from the main thread on every keystroke - no separate command-layer cache is
 * needed. See [AssetService.suggestNames]'s KDoc for the full rationale.
 */
object AssetNameSuggestions {

    fun forService(assetService: AssetService): SuggestionProvider<CommandSourceStack> =
        SuggestionProvider { _, builder ->
            for (name in assetService.suggestNames(builder.remainingLowerCase)) {
                builder.suggest(name)
            }
            CompletableFuture.completedFuture<Suggestions>(builder.build())
        }
}
