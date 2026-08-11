package io.github.potetogroove.schemdepot.config

import org.bukkit.configuration.file.FileConfiguration
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

        private fun requireNonBlankPath(
            value: String?,
            default: String,
            key: String,
            logger: Logger,
        ): String {
            if (value.isNullOrBlank()) {
                logger.warning("config.yml: $key must not be blank. Falling back to \"$default\".")
                return default
            }
            return value
        }
    }
}
