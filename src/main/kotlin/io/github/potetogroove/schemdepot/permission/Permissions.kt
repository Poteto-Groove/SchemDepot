package io.github.potetogroove.schemdepot.permission

/**
 * Centralized permission-node constants (docs/SchemDepot_DESIGN.md SS7).
 *
 * Every permission check in SchemDepot ([io.github.potetogroove.schemdepot.asset.AssetService],
 * and the Phase 6 command layer) must reference these constants rather than hardcoding a
 * permission-node string literal, so the node set stays centralized in one place (mirrors the
 * SS6.2 reserved-name centralization pattern already used by
 * [io.github.potetogroove.schemdepot.asset.AssetName]).
 */
object Permissions {

    /** Parent/common permission; SS7 suggests granting this to allow baseline plugin usage. */
    const val USE = "schemdepot.use"

    const val PASTE = "schemdepot.paste"
    const val LIST = "schemdepot.list"
    const val INFO = "schemdepot.info"
    const val ADD = "schemdepot.add"

    const val RENAME_OWN = "schemdepot.rename.own"
    const val RENAME_ANY = "schemdepot.rename.any"

    const val REMOVE_OWN = "schemdepot.remove.own"
    const val REMOVE_ANY = "schemdepot.remove.any"

    /** Grants all SchemDepot permissions (SS7). */
    const val ADMIN = "schemdepot.admin"
}
