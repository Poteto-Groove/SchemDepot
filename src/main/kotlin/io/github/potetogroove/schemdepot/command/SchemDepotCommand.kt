package io.github.potetogroove.schemdepot.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.potetogroove.schemdepot.asset.AddResult
import io.github.potetogroove.schemdepot.asset.AssetService
import io.github.potetogroove.schemdepot.asset.InfoResult
import io.github.potetogroove.schemdepot.asset.ListResult
import io.github.potetogroove.schemdepot.asset.PasteResult
import io.github.potetogroove.schemdepot.asset.RemoveResult
import io.github.potetogroove.schemdepot.asset.RenameResult
import io.github.potetogroove.schemdepot.message.Messages
import io.github.potetogroove.schemdepot.permission.Permissions
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Builds and executes the `/schemdepot` (alias `/sd`) Brigadier command tree
 * (docs/SchemDepot_DESIGN.md SS6 "Command tree", SS7 "Permissions", SS18 "Tab Completion",
 * SS29 Phase 6).
 *
 * ## Command tree (SS6.1)
 * ```
 * /sd                                  (help; requires schemdepot.use)
 * /sd help                             (requires schemdepot.use)
 * /sd add <name>                       (requires schemdepot.add; player only)
 * /sd paste <name>                     (requires schemdepot.paste; player only)
 * /sd <name>                           (shortcut for "paste"; requires schemdepot.paste; player only)
 * /sd list [page]                      (requires schemdepot.list)
 * /sd info <name>                      (requires schemdepot.info)
 * /sd rename <name> <new-name>         (requires schemdepot.rename.own or .rename.any)
 * /sd remove <name>                    (requires schemdepot.remove.own or .remove.any)
 * ```
 * The bare `/sd <name>` node is registered as a sibling `argument` node alongside the literal
 * subcommands rather than nested under `paste`. Brigadier always tries every literal child of a
 * node before falling back to an argument child (SS6.1: "The literal subcommands take priority;
 * `/sd <name>` is only tried when no literal matches"), so e.g. `/sd list` still resolves to the
 * `list` subcommand rather than being swallowed by this argument node, even though `list` is also
 * a syntactically valid (if reserved, see [AssetName]) argument value.
 *
 * ## Threading (SS12/AC-12)
 * Paper dispatches Brigadier command execution on the main server thread (the same thread Bukkit
 * has always executed `/`-commands on), which satisfies [AssetService.add]/[AssetService.paste]'s
 * documented main-thread requirement. This is standard Paper/Bukkit command-dispatch behaviour,
 * not something asserted by any of the javap-verified type signatures above - see the Phase 6
 * report for this call-out and its recommended Phase 7 real-server confirmation.
 *
 * ## Permission model / console (SS7, SS21-4, SS21-11)
 * Every subcommand node carries its own `.requires(...)` predicate (SS7: "each subcommand must
 * also require its own permission node"); the root node requires [Permissions.USE]. `add`,
 * `paste`, and the direct-name shortcut additionally reject non-player senders with
 * [Messages.playerOnly] (SS7: "Console cannot paste/add - it has no location/clipboard").
 * `rename`/`remove` ownership is decided by comparing [Permissions.RENAME_OWN]/[Permissions.REMOVE_OWN]
 * against [io.github.potetogroove.schemdepot.asset.Asset.authorUuid] inside [AssetService] itself;
 * a non-player caller (console) is mapped to [CONSOLE_UUID], a sentinel that can never equal a
 * real player's UUID, so console-driven rename/remove always requires the `.any` permission - see
 * the Phase 6 report for the full rationale.
 */
class SchemDepotCommand(private val assetService: AssetService) {

    private val suggestionProvider = AssetNameSuggestions.forService(assetService)

    /**
     * Builds the full `/schemdepot` command tree described in the class KDoc (SS6.1).
     *
     * The returned node's literal is the **primary root** `schemdepot` (SS3.2/SS26). The `/sd`
     * alias is attached at registration time by `SchemDepotPlugin.onEnable`, not here.
     */
    fun buildNode(): LiteralCommandNode<CommandSourceStack> {
        val nameArgument = Commands.argument("name", StringArgumentType.word())
            .suggests(suggestionProvider)

        // SS3.2/SS26: the primary root literal is `/schemdepot`; `/sd` is registered as an *alias*
        // by SchemDepotPlugin (Commands.register(node, description, aliases)), never as the root
        // literal itself. Building the node as literal("sd") makes Paper register only `/sd` and
        // leaves `/schemdepot` undefined, which contradicts the descriptor requirement in SS26.
        return Commands.literal("schemdepot")
            .requires { hasAny(it, Permissions.USE) }
            .executes(::executeHelp)
            .then(Commands.literal("help").executes(::executeHelp))
            .then(
                Commands.literal("add")
                    .requires { hasAny(it, Permissions.ADD) }
                    .then(
                        Commands.argument("name", StringArgumentType.word())
                            .executes(::executeAdd),
                    ),
            )
            .then(
                Commands.literal("paste")
                    .requires { hasAny(it, Permissions.PASTE) }
                    .then(
                        Commands.argument("name", StringArgumentType.word())
                            .suggests(suggestionProvider)
                            .executes(::executePaste),
                    ),
            )
            .then(
                Commands.literal("list")
                    .requires { hasAny(it, Permissions.LIST) }
                    .executes { context -> executeList(context, 1) }
                    .then(
                        Commands.argument("page", IntegerArgumentType.integer(1))
                            .executes { context -> executeList(context, IntegerArgumentType.getInteger(context, "page")) },
                    ),
            )
            .then(
                Commands.literal("info")
                    .requires { hasAny(it, Permissions.INFO) }
                    .then(
                        Commands.argument("name", StringArgumentType.word())
                            .suggests(suggestionProvider)
                            .executes(::executeInfo),
                    ),
            )
            .then(
                Commands.literal("rename")
                    .requires { hasAny(it, Permissions.RENAME_OWN, Permissions.RENAME_ANY) }
                    .then(
                        Commands.argument("name", StringArgumentType.word())
                            .suggests(suggestionProvider)
                            .then(
                                Commands.argument("new-name", StringArgumentType.word())
                                    .executes(::executeRename),
                            ),
                    ),
            )
            .then(
                Commands.literal("remove")
                    .requires { hasAny(it, Permissions.REMOVE_OWN, Permissions.REMOVE_ANY) }
                    .then(
                        Commands.argument("name", StringArgumentType.word())
                            .suggests(suggestionProvider)
                            .executes(::executeRemove),
                    ),
            )
            .then(
                nameArgument
                    .requires { hasAny(it, Permissions.PASTE) }
                    .executes(::executePaste),
            )
            .build()
    }

    private fun hasAny(source: CommandSourceStack, vararg nodes: String): Boolean =
        nodes.any { source.sender.hasPermission(it) }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) {
            return sender
        }
        sender.sendMessage(Messages.playerOnly())
        return null
    }

    private fun callerUuidOf(sender: CommandSender): UUID = (sender as? Player)?.uniqueId ?: CONSOLE_UUID

    // -------------------------------------------------------------------------------------
    // Handlers
    // -------------------------------------------------------------------------------------

    private fun executeHelp(context: CommandContext<CommandSourceStack>): Int {
        context.source.sender.sendMessage(Messages.help())
        return Command.SINGLE_SUCCESS
    }

    private fun executeAdd(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context.source.sender) ?: return Command.SINGLE_SUCCESS
        val rawName = StringArgumentType.getString(context, "name")
        assetService.add(player, rawName) { result ->
            when (result) {
                is AddResult.Success -> player.sendMessage(Messages.added(result.asset))
                AddResult.NoPermission -> player.sendMessage(Messages.noPermissionAdd())
                is AddResult.InvalidName -> player.sendMessage(Messages.invalidName(result.name))
                is AddResult.ReservedName -> player.sendMessage(Messages.reservedName(result.name))
                AddResult.EmptyClipboard -> player.sendMessage(Messages.emptyClipboard())
                is AddResult.DuplicateName -> player.sendMessage(Messages.alreadyExists(result.name))
                is AddResult.VolumeLimitExceeded ->
                    player.sendMessage(Messages.volumeLimitExceeded(result.volume, result.maxVolume))
                is AddResult.InternalError -> player.sendMessage(Messages.internalError())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executePaste(context: CommandContext<CommandSourceStack>): Int {
        val player = requirePlayer(context.source.sender) ?: return Command.SINGLE_SUCCESS
        val rawName = StringArgumentType.getString(context, "name")
        assetService.paste(player, rawName) { result ->
            when (result) {
                is PasteResult.Success -> player.sendMessage(Messages.pasted(result.asset))
                PasteResult.NoPermission -> player.sendMessage(Messages.noPermissionPaste())
                is PasteResult.NotFound -> player.sendMessage(Messages.notFound(result.name))
                is PasteResult.AssetFileUnavailable -> player.sendMessage(Messages.assetFileUnavailable())
                is PasteResult.InternalError -> player.sendMessage(Messages.internalError())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeList(context: CommandContext<CommandSourceStack>, page: Int): Int {
        val sender = context.source.sender
        assetService.list(page, sender::hasPermission) { result ->
            when (result) {
                is ListResult.Success -> sender.sendMessage(Messages.listPage(result.assets, result.page, result.totalPages))
                ListResult.NoPermission -> sender.sendMessage(Messages.noPermissionList())
                is ListResult.InternalError -> sender.sendMessage(Messages.internalError())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeInfo(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        val rawName = StringArgumentType.getString(context, "name")
        assetService.info(rawName, sender::hasPermission) { result ->
            when (result) {
                is InfoResult.Success -> sender.sendMessage(Messages.info(result.asset))
                InfoResult.NoPermission -> sender.sendMessage(Messages.noPermissionInfo())
                is InfoResult.NotFound -> sender.sendMessage(Messages.notFound(result.name))
                is InfoResult.InternalError -> sender.sendMessage(Messages.internalError())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeRename(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        val callerUuid = callerUuidOf(sender)
        val oldRawName = StringArgumentType.getString(context, "name")
        val newRawName = StringArgumentType.getString(context, "new-name")
        assetService.rename(callerUuid, oldRawName, newRawName, sender::hasPermission) { result ->
            when (result) {
                // oldRawName (not a canonical display name) is intentional here: the command
                // layer no longer keeps its own name cache (see AssetService.suggestNames), and
                // AssetService.rename's success path only hands back the *new* Asset - not
                // whatever display-case the pre-rename asset had. Players overwhelmingly type the
                // existing name back verbatim, so this loses nothing in practice.
                is RenameResult.Success -> sender.sendMessage(Messages.renamed(oldRawName, result.asset.name))
                RenameResult.NoPermission -> sender.sendMessage(Messages.noPermissionRename())
                is RenameResult.NotFound -> sender.sendMessage(Messages.notFound(result.name))
                is RenameResult.InvalidName -> sender.sendMessage(Messages.invalidName(result.name))
                is RenameResult.ReservedName -> sender.sendMessage(Messages.reservedName(result.name))
                is RenameResult.DuplicateName -> sender.sendMessage(Messages.alreadyExists(result.name))
                is RenameResult.InternalError -> sender.sendMessage(Messages.internalError())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun executeRemove(context: CommandContext<CommandSourceStack>): Int {
        val sender = context.source.sender
        val callerUuid = callerUuidOf(sender)
        val rawName = StringArgumentType.getString(context, "name")
        assetService.remove(callerUuid, rawName, sender::hasPermission) { result ->
            when (result) {
                is RemoveResult.Success -> sender.sendMessage(Messages.removed(result.asset))
                RemoveResult.NoPermission -> sender.sendMessage(Messages.noPermissionRemove())
                is RemoveResult.NotFound -> sender.sendMessage(Messages.notFound(result.name))
                is RemoveResult.InternalError -> sender.sendMessage(Messages.internalError())
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private companion object {
        /**
         * Sentinel caller UUID used for `rename`/`remove` when the sender is not a [Player] (i.e.
         * console/command blocks). [AssetService.rename]/[AssetService.remove] compare this UUID
         * against [io.github.potetogroove.schemdepot.asset.Asset.authorUuid] to decide `.own` vs
         * `.any`; since [AssetService.add] requires a live [Player] (SS7), no real asset can ever
         * be authored under an all-zero UUID, so a non-player caller always falls into the `.any`
         * branch - matching SS7's intent that console-driven administrative rename/remove
         * requires `.rename.any`/`.remove.any`. See the Phase 6 report for this design call-out.
         */
        val CONSOLE_UUID: UUID = UUID(0L, 0L)
    }
}
