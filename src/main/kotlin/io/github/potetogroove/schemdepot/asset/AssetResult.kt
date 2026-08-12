package io.github.potetogroove.schemdepot.asset

/**
 * Result types returned by [AssetService]'s six operations (docs/SchemDepot_DESIGN.md SS31).
 *
 * Exceptions are never used for expected control flow at this boundary (SS30-21): every outcome
 * the Phase 6 command layer needs to render a distinct user-facing message for (SS17) is a case
 * of one of these sealed interfaces. Any `cause`/exception field is included purely for
 * server-log diagnostics; it must never be shown to a player verbatim (SS21-8).
 */

/** Result of [AssetService.add] (SS13.1, AC-01/AC-02/AC-03). */
sealed interface AddResult {

    /** The clipboard was written to disk and registered as [asset]. */
    data class Success(val asset: Asset) : AddResult

    /** The caller lacks `schemdepot.add`. */
    data object NoPermission : AddResult

    /** [name] did not match [AssetName]'s format rules. */
    data class InvalidName(val name: String) : AddResult

    /** [name] collides with a reserved command word (SS6.2). */
    data class ReservedName(val name: String) : AddResult

    /** The player has no WorldEdit clipboard (has not run `//copy`/`//cut`). */
    data object EmptyClipboard : AddResult

    /** An asset with this normalized name already exists. */
    data class DuplicateName(val name: String) : AddResult

    /** The clipboard's bounding-box volume exceeds `limits.max-volume` (SS16). */
    data class VolumeLimitExceeded(val volume: Long, val maxVolume: Long) : AddResult

    /** Storage/database failure. [cause] is safe to log, never to show to a player (SS21-8). */
    data class InternalError(val cause: Exception) : AddResult
}

/** Result of [AssetService.paste] (SS13.2, AC-04/AC-05/AC-11). */
sealed interface PasteResult {

    /** The asset pasted successfully and was registered in the player's WorldEdit undo history. */
    data class Success(val asset: Asset) : PasteResult

    /** The caller lacks `schemdepot.paste`. */
    data object NoPermission : PasteResult

    /** No asset is registered under this name. */
    data class NotFound(val name: String) : PasteResult

    /**
     * The backing `.schem` is missing, unreadable, or in an unrecognised format (AC-11:
     * "The asset file is missing or corrupted. Contact an administrator.").
     */
    data class AssetFileUnavailable(val asset: Asset) : PasteResult

    /** The WorldEdit paste itself failed unexpectedly. [cause] is safe to log, not to show. */
    data class InternalError(val cause: Exception) : PasteResult
}

/** Result of [AssetService.list] (SS5.3, SS27.1 pagination). */
sealed interface ListResult {

    /**
     * @param page the 1-based page actually returned (clamped into `[1, totalPages]`).
     * @param totalPages always >= 1, even when [totalCount] is 0.
     */
    data class Success(
        val assets: List<Asset>,
        val page: Int,
        val pageSize: Int,
        val totalCount: Long,
        val totalPages: Int,
    ) : ListResult

    /** The caller lacks `schemdepot.list`. */
    data object NoPermission : ListResult

    /** Database failure. [cause] is safe to log, never to show to a player (SS21-8). */
    data class InternalError(val cause: Exception) : ListResult
}

/** Result of [AssetService.info] (SS5.4). */
sealed interface InfoResult {

    data class Success(val asset: Asset) : InfoResult

    /** The caller lacks `schemdepot.info`. */
    data object NoPermission : InfoResult

    /** No asset is registered under this name. */
    data class NotFound(val name: String) : InfoResult

    /** Database failure. [cause] is safe to log, never to show to a player (SS21-8). */
    data class InternalError(val cause: Exception) : InfoResult
}

/** Result of [AssetService.rename] (SS13.3, AC-09, AC-10). */
sealed interface RenameResult {

    /** [asset] already reflects the new name; `schematicFile` is guaranteed unchanged (AC-09). */
    data class Success(val asset: Asset) : RenameResult

    /**
     * The caller neither owns the asset with `.rename.own` nor holds `.rename.any` (AC-10;
     * ownership decided strictly by [Asset.authorUuid], never by display name).
     */
    data object NoPermission : RenameResult

    /** No asset is registered under [name]. */
    data class NotFound(val name: String) : RenameResult

    /** The requested new name did not match [AssetName]'s format rules. */
    data class InvalidName(val name: String) : RenameResult

    /** The requested new name collides with a reserved command word (SS6.2). */
    data class ReservedName(val name: String) : RenameResult

    /** Another asset already uses the requested new normalized name. */
    data class DuplicateName(val name: String) : RenameResult

    /** Database failure. [cause] is safe to log, never to show to a player (SS21-8). */
    data class InternalError(val cause: Exception) : RenameResult
}

/** Result of [AssetService.remove] (SS13.4, AC-10). */
sealed interface RemoveResult {

    /** [asset] as it existed immediately before removal. */
    data class Success(val asset: Asset) : RemoveResult

    /**
     * The caller neither owns the asset with `.remove.own` nor holds `.remove.any` (AC-10;
     * ownership decided strictly by [Asset.authorUuid], never by display name).
     */
    data object NoPermission : RemoveResult

    /** No asset is registered under [name]. */
    data class NotFound(val name: String) : RemoveResult

    /** Database/storage failure. [cause] is safe to log, never to show to a player (SS21-8). */
    data class InternalError(val cause: Exception) : RemoveResult
}
