package io.github.potetogroove.schemdepot.config

import org.bukkit.configuration.file.FileConfiguration
import java.nio.file.InvalidPathException
import java.nio.file.Paths
import java.util.logging.Logger

/**
 * Type-safe representation of `config.yml`.
 *
 * See docs/SchemDepot_DESIGN.md SS16 for the authoritative field list.
 */
data class SchemDepotConfig(
    val storage: Storage,
    val list: ListSettings,
    val paste: PasteSettings,
    val limits: Limits,
) {

    data class Storage(
        val databaseFile: String,
        val schematicsDirectory: String,
        val tempDirectory: String,
        val trashDirectory: String,
    )

    data class ListSettings(
        val pageSize: Int,
    )

    data class PasteSettings(
        val ignoreAirByDefault: Boolean,
        val includeEntities: Boolean,
        val includeBiomes: Boolean,
    )

    data class Limits(
        /** 0 means "use FAWE/server limits only" (no SchemDepot-specific cap). */
        val maxVolume: Long,
    )

    companion object {

        private const val DEFAULT_DATABASE_FILE = "assets.db"
        private const val DEFAULT_SCHEMATICS_DIRECTORY = "schematics"
        private const val DEFAULT_TEMP_DIRECTORY = "tmp"
        private const val DEFAULT_TRASH_DIRECTORY = "trash"
        private const val DEFAULT_PAGE_SIZE = 8
        private const val DEFAULT_MAX_VOLUME = 0L

        /**
         * Loads and validates a [SchemDepotConfig] from the given [FileConfiguration].
         *
         * Invalid values are normalized to a safe default and logged as a warning rather than
         * silently accepted, per design doc SS16 ("Invalid values should be normalized or cause
         * a clear startup error rather than silent undefined behavior").
         */
        fun load(config: FileConfiguration, logger: Logger): SchemDepotConfig {
            val databaseFile = requireNonBlankPath(
                config.getString("storage.database-file"),
                DEFAULT_DATABASE_FILE,
                "storage.database-file",
                logger,
            )
            val schematicsDirectory = requireNonBlankPath(
                config.getString("storage.schematics-directory"),
                DEFAULT_SCHEMATICS_DIRECTORY,
                "storage.schematics-directory",
                logger,
            )
            val tempDirectory = requireNonBlankPath(
                config.getString("storage.temp-directory"),
                DEFAULT_TEMP_DIRECTORY,
                "storage.temp-directory",
                logger,
            )
            val trashDirectory = requireNonBlankPath(
                config.getString("storage.trash-directory"),
                DEFAULT_TRASH_DIRECTORY,
                "storage.trash-directory",
                logger,
            )

            var pageSize = config.getInt("list.page-size", DEFAULT_PAGE_SIZE)
            if (pageSize < 1) {
                logger.warning(
                    "config.yml: list.page-size must be >= 1, but was $pageSize. " +
                        "Falling back to $DEFAULT_PAGE_SIZE.",
                )
                pageSize = DEFAULT_PAGE_SIZE
            }

            val ignoreAirByDefault = config.getBoolean("paste.ignore-air-by-default", false)
            val includeEntities = config.getBoolean("paste.include-entities", false)
            val includeBiomes = config.getBoolean("paste.include-biomes", false)

            var maxVolume = config.getLong("limits.max-volume", DEFAULT_MAX_VOLUME)
            if (maxVolume < 0) {
                logger.warning(
                    "config.yml: limits.max-volume must be >= 0, but was $maxVolume. " +
                        "Falling back to $DEFAULT_MAX_VOLUME (no SchemDepot-specific limit).",
                )
                maxVolume = DEFAULT_MAX_VOLUME
            }

            return SchemDepotConfig(
                storage = Storage(
                    databaseFile = databaseFile,
                    schematicsDirectory = schematicsDirectory,
                    tempDirectory = tempDirectory,
                    trashDirectory = trashDirectory,
                ),
                list = ListSettings(pageSize = pageSize),
                paste = PasteSettings(
                    ignoreAirByDefault = ignoreAirByDefault,
                    includeEntities = includeEntities,
                    includeBiomes = includeBiomes,
                ),
                limits = Limits(maxVolume = maxVolume),
            )
        }

        /** Characters that can only appear in a value that is trying to be more than a name. */
        private val PATH_COMPONENT_SEPARATORS = charArrayOf('/', '\\', ':')

        /**
         * Validates a `storage.*` value as a **single** file or directory name to be resolved
         * inside the plugin's own data folder, falling back to [default] with a warning otherwise
         * (SS16: "Invalid values should be normalized or cause a clear startup error rather than
         * silent undefined behavior").
         *
         * Every one of these values is fed to `dataFolder.resolve(value)`, and `Path.resolve`
         * happily accepts an absolute path (returning it verbatim) or a `../..` chain. Without
         * this check, `schematics-directory: "../../../world/region"` or
         * `database-file: "/etc/passwd"` would point SchemDepot's create/write/delete operations
         * at arbitrary locations outside the data folder, which is the same class of escape SS21-1
         * and SS21-10 forbid for asset-derived paths.
         *
         * Exposed as `internal` so it can be unit-tested without a Bukkit `FileConfiguration`
         * (SS27.1); [load] is the only production caller.
         */
        internal fun requireNonBlankPath(
            value: String?,
            default: String,
            key: String,
            logger: Logger,
        ): String {
            if (value.isNullOrBlank()) {
                logger.warning("config.yml: $key must not be blank. Falling back to \"$default\".")
                return default
            }

            val rejection = pathComponentRejectionReason(value)
            if (rejection != null) {
                logger.warning(
                    "config.yml: $key must be a single file or directory name located directly " +
                        "inside the plugin data folder, but was \"$value\" ($rejection). " +
                        "Falling back to \"$default\".",
                )
                return default
            }

            return value
        }

        /**
         * Returns a human-readable reason why [value] is not a plain single path component, or
         * `null` if it is one.
         *
         * The character check is done before the [Path] check on purpose: `\` and `:` are ordinary
         * filename characters on Linux, so `Paths.get("..\\..\\world")` would look like a single
         * harmless name there while meaning traversal on the Windows servers this plugin also has
         * to be safe on.
         */
        private fun pathComponentRejectionReason(value: String): String? {
            if (value == "." || value == "..") {
                return "it is a relative-path element, not a name"
            }
            if (value.any { it in PATH_COMPONENT_SEPARATORS }) {
                return "it contains a path separator or a drive letter"
            }
            val path = try {
                Paths.get(value)
            } catch (e: InvalidPathException) {
                return "it is not a valid file name on this platform"
            }
            if (path.isAbsolute || path.root != null) {
                return "it is an absolute path"
            }
            if (path.nameCount != 1) {
                return "it contains more than one path element"
            }
            return null
        }
    }
}
