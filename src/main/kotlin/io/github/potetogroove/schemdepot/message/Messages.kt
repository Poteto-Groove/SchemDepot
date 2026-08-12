package io.github.potetogroove.schemdepot.message

import io.github.potetogroove.schemdepot.asset.Asset
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.JoinConfiguration
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Single authoritative source for every SchemDepot player-facing message
 * (docs/SchemDepot_DESIGN.md SS17 "User-facing Messages", SS18 "Tab Completion" display notes for
 * `/sd list`/`/sd info` which actually live in SS5.3/SS5.4).
 *
 * Every string that SS17 spells out literally ("Success"/"Errors" blocks) is reproduced here
 * **character-for-character**, with only the asset name(s) substituted in. Messages SS17 does not
 * literally provide (e.g. per-operation "no permission", "invalid name", internal-error text) are
 * new but follow SS17's tone: concise, `[SchemDepot]`-prefixed, and never exposing a raw
 * exception, filesystem path, or UUID to the player (SS17 tail, SS21-8).
 *
 * All Adventure types used here ([Component], [NamedTextColor], [ClickEvent], [HoverEvent]) were
 * verified via `javap` against the `net.kyori:adventure-api` jar that `paper-api` pulls in
 * transitively (see the Phase 6 report).
 */
object Messages {

    private const val PREFIX = "[SchemDepot] "

    private val DATE_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    private fun success(text: String): Component = Component.text(PREFIX + text, NamedTextColor.GREEN)

    private fun error(text: String): Component = Component.text(PREFIX + text, NamedTextColor.RED)

    // ---------------------------------------------------------------------------------------
    // Success messages (SS17 "Success:" block, reproduced verbatim)
    // ---------------------------------------------------------------------------------------

    /**
     * `[SchemDepot] Registered "OakTree".` followed by the SS5.1 example's unprefixed
     * `Author: warasugi` / `Size: 13 x 18 x 12` lines.
     */
    fun added(asset: Asset): Component {
        val header = success("Registered \"${asset.name}\".")
        val author = Component.text("Author: ${asset.authorName}", NamedTextColor.GRAY)
        val size = Component.text(
            "Size: ${asset.sizeX} x ${asset.sizeY} x ${asset.sizeZ}",
            NamedTextColor.GRAY,
        )
        return Component.join(JoinConfiguration.newlines(), header, author, size)
    }

    /** `[SchemDepot] Pasted "OakTree".` */
    fun pasted(asset: Asset): Component = success("Pasted \"${asset.name}\".")

    /** `[SchemDepot] Renamed "OakTree" to "LargeOakTree".` */
    fun renamed(oldName: String, newName: String): Component =
        success("Renamed \"$oldName\" to \"$newName\".")

    /** `[SchemDepot] Removed "OakTree".` */
    fun removed(asset: Asset): Component = success("Removed \"${asset.name}\".")

    // ---------------------------------------------------------------------------------------
    // Error messages (SS17 "Errors:" block, reproduced verbatim)
    // ---------------------------------------------------------------------------------------

    /** `[SchemDepot] Your WorldEdit clipboard is empty. Use //copy first.` */
    fun emptyClipboard(): Component = error("Your WorldEdit clipboard is empty. Use //copy first.")

    /** `[SchemDepot] Asset "OakTree" already exists.` */
    fun alreadyExists(name: String): Component = error("Asset \"$name\" already exists.")

    /** `[SchemDepot] Asset "OakTree" was not found.` */
    fun notFound(name: String): Component = error("Asset \"$name\" was not found.")

    /** `[SchemDepot] "list" is a reserved asset name.` */
    fun reservedName(name: String): Component = error("\"$name\" is a reserved asset name.")

    /** `[SchemDepot] You do not have permission to remove this asset.` (SS17, exact). */
    fun noPermissionRemove(): Component = noPermission("remove this asset")

    /** `[SchemDepot] The asset file is missing or corrupted. Contact an administrator.` */
    fun assetFileUnavailable(): Component =
        error("The asset file is missing or corrupted. Contact an administrator.")

    // ---------------------------------------------------------------------------------------
    // Additional messages - not literally specified by SS17, kept in the same tone (SS17 tail:
    // "Messages should remain concise", never expose raw exceptions/paths/UUIDs).
    // ---------------------------------------------------------------------------------------

    private fun noPermission(action: String): Component = error("You do not have permission to $action.")

    fun noPermissionAdd(): Component = noPermission("add assets")

    fun noPermissionPaste(): Component = noPermission("paste assets")

    fun noPermissionList(): Component = noPermission("view the asset list")

    fun noPermissionInfo(): Component = noPermission("view asset info")

    fun noPermissionRename(): Component = noPermission("rename this asset")

    /** [name] failed [io.github.potetogroove.schemdepot.asset.AssetName]'s format rules (SS6.3). */
    fun invalidName(name: String): Component = error("\"$name\" is not a valid asset name.")

    /** The clipboard's bounding-box volume exceeds `limits.max-volume` (SS16). */
    fun volumeLimitExceeded(volume: Long, maxVolume: Long): Component =
        error("This clipboard's volume ($volume blocks) exceeds the configured limit ($maxVolume blocks).")

    /**
     * Generic fallback for every `InternalError`/storage-failure result across
     * [io.github.potetogroove.schemdepot.asset.AssetResult]. The causing exception is logged
     * server-side by [io.github.potetogroove.schemdepot.asset.AssetService]; it must never reach
     * the player (SS21-8).
     */
    fun internalError(): Component = error("An internal error occurred. Contact an administrator.")

    /** `add`/`paste`/the direct-paste shortcut all require a live player (SS7). */
    fun playerOnly(): Component = error("This command can only be used by a player.")

    // ---------------------------------------------------------------------------------------
    // Help (SS6.1 command tree; wording is not literally specified by the design doc)
    // ---------------------------------------------------------------------------------------

    fun help(): Component {
        val lines = listOf(
            Component.text(PREFIX + "Commands:", NamedTextColor.GREEN),
            helpLine("/sd add <name>", "Register your WorldEdit clipboard as a new asset."),
            helpLine("/sd paste <name>", "Paste a registered asset (same as /sd <name>)."),
            helpLine("/sd <name>", "Shortcut for /sd paste <name>."),
            helpLine("/sd list [page]", "List registered assets."),
            helpLine("/sd info <name>", "Show details about an asset."),
            helpLine("/sd rename <name> <new-name>", "Rename an asset."),
            helpLine("/sd remove <name>", "Remove an asset."),
            helpLine("/sd help", "Show this message."),
        )
        return Component.join(JoinConfiguration.newlines(), lines)
    }

    private fun helpLine(usage: String, description: String): Component =
        Component.text(usage, NamedTextColor.AQUA).append(Component.text(" - $description", NamedTextColor.GRAY))

    // ---------------------------------------------------------------------------------------
    // list (SS5.3)
    // ---------------------------------------------------------------------------------------

    /**
     * Renders one page of `/sd list` (SS5.3). Each asset name is clickable (suggests
     * `/sd <name>` in the sender's chat input, per SS5.3 "Asset name is clickable and suggests or
     * runs `/sd <name>`") and its hover text shows author/created/size (SS5.3).
     */
    fun listPage(assets: List<Asset>, page: Int, totalPages: Int): Component {
        val header = Component.text("SchemDepot — Assets $page/$totalPages", NamedTextColor.GOLD)
        if (assets.isEmpty()) {
            return Component.join(
                JoinConfiguration.newlines(),
                header,
                Component.empty(),
                Component.text("(no assets registered)", NamedTextColor.GRAY),
            )
        }

        val rows = assets.map(::listRow)
        return Component.join(JoinConfiguration.newlines(), listOf(header, Component.empty()) + rows)
    }

    private fun listRow(asset: Asset): Component {
        val rowText = "%-16s %-14s %dx%dx%d".format(
            asset.name,
            asset.authorName,
            asset.sizeX,
            asset.sizeY,
            asset.sizeZ,
        )
        val hover = Component.join(
            JoinConfiguration.newlines(),
            Component.text("Author: ${asset.authorName}"),
            Component.text("Created: ${DATE_FORMATTER.format(asset.createdAt)}"),
            Component.text("Size: ${asset.sizeX} x ${asset.sizeY} x ${asset.sizeZ}"),
        )
        return Component.text(rowText, NamedTextColor.AQUA)
            .clickEvent(ClickEvent.suggestCommand("/sd ${asset.name}"))
            .hoverEvent(HoverEvent.showText(hover))
    }

    // ---------------------------------------------------------------------------------------
    // info (SS5.4)
    // ---------------------------------------------------------------------------------------

    /**
     * Renders `/sd info <name>` exactly as the SS5.4 example (four unprefixed `Field: value`
     * lines). Does not expose the asset's UUID or backing filesystem path (SS5.4, SS21-8).
     */
    fun info(asset: Asset): Component {
        val lines = listOf(
            Component.text("Name: ${asset.name}"),
            Component.text("Author: ${asset.authorName}"),
            Component.text("Created: ${DATE_FORMATTER.format(asset.createdAt)}"),
            Component.text("Size: ${asset.sizeX} x ${asset.sizeY} x ${asset.sizeZ}"),
        )
        return Component.join(JoinConfiguration.newlines(), lines)
    }
}
