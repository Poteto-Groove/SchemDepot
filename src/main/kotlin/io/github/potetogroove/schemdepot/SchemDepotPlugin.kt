package io.github.potetogroove.schemdepot

import io.github.potetogroove.schemdepot.asset.AssetService
import io.github.potetogroove.schemdepot.config.SchemDepotConfig
import io.github.potetogroove.schemdepot.storage.Database
import io.github.potetogroove.schemdepot.storage.DatabaseMigration
import io.github.potetogroove.schemdepot.storage.SchematicStorage
import io.github.potetogroove.schemdepot.storage.SqliteAssetRepository
import io.github.potetogroove.schemdepot.worldedit.ClipboardService
import io.github.potetogroove.schemdepot.worldedit.PasteService
import io.github.potetogroove.schemdepot.worldedit.WorldEditFacade
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level

/**
 * SchemDepot plugin entry point.
 *
 * Wires the full object graph (docs/SchemDepot_DESIGN.md SS29 Phase 5):
 * [Database] -> [DatabaseMigration] -> [SqliteAssetRepository] -> [SchematicStorage] ->
 * [WorldEditFacade] -> [ClipboardService] / [PasteService] -> [AssetService], then runs the
 * SS19 startup sequence (directory init, schema migration, temp cleanup, index load).
 *
 * Command registration is deliberately **not** part of this class - that is Phase 6's
 * responsibility (design doc SS29 Phase 6).
 */
class SchemDepotPlugin : JavaPlugin() {

    /**
     * Bounded worker executor for SQLite/filesystem/schematic I/O.
     * Must never be used to touch Bukkit world/entity/player APIs (SS12 of the design doc).
     */
    lateinit var workerExecutor: ExecutorService
        private set

    /** Loaded/validated `config.yml` (SS16). Exposed for the Phase 6 command layer. */
    lateinit var schemDepotConfig: SchemDepotConfig
        private set

    /** Application service coordinating the registry (SS15.2). Exposed for the Phase 6 command layer. */
    lateinit var assetService: AssetService
        private set

    override fun onEnable() {
        saveDefaultConfig()
        reloadConfig()
        val loadedConfig = SchemDepotConfig.load(config, logger)
        schemDepotConfig = loadedConfig

        val schematicStorage = SchematicStorage(
            dataDirectory = dataFolder.toPath(),
            schematicsDirectoryName = loadedConfig.storage.schematicsDirectory,
            tempDirectoryName = loadedConfig.storage.tempDirectory,
            trashDirectoryName = loadedConfig.storage.trashDirectory,
            logger = logger,
        )

        // SS19 startup sequence, run synchronously in onEnable (no commands are registered yet,
        // so there is no risk of a command being dispatched before the index is ready - see the
        // Phase 5 report for the rationale of running this blocking rather than on the worker
        // executor). Any failure disables the plugin rather than running in a partially
        // initialized state (SS20.4).
        if (!runStartupStep("initialize the plugin data directories") {
                schematicStorage.initializeDirectories()
            }
        ) {
            return
        }

        val database = Database(File(dataFolder, loadedConfig.storage.databaseFile))
        if (!runStartupStep("migrate the SQLite registry schema") {
                database.openConnection().use { connection -> DatabaseMigration.migrate(connection) }
            }
        ) {
            return
        }

        val repository = SqliteAssetRepository(database)
        val worldEditFacade = WorldEditFacade()
        val clipboardService = ClipboardService(worldEditFacade)
        val pasteService = PasteService(worldEditFacade)

        workerExecutor = Executors.newFixedThreadPool(2, WorkerThreadFactory())

        val service = AssetService(
            repository = repository,
            schematicStorage = schematicStorage,
            clipboardService = clipboardService,
            pasteService = pasteService,
            config = loadedConfig,
            workerExecutor = workerExecutor,
            mainThreadDispatcher = ::dispatchToMainThread,
        )
        assetService = service

        val cleanupResult = schematicStorage.cleanupTempDirectory()
        if (cleanupResult.deletedCount > 0) {
            logger.info("Cleaned up ${cleanupResult.deletedCount} leftover temp file(s) from a previous run.")
        }
        // Individual per-file cleanup failures are already logged as WARNING by
        // SchematicStorage.cleanupTempDirectory itself and are not fatal to startup (SS20).

        if (!runStartupStep("load the asset index") { service.loadIndexBlocking() }) {
            return
        }

        logger.info("SchemDepot enabled (data folder: ${dataFolder.absolutePath}).")
    }

    override fun onDisable() {
        if (::workerExecutor.isInitialized) {
            shutdownWorkerExecutor()
        }
        logger.info("SchemDepot disabled.")
    }

    /**
     * Runs a single blocking startup step. On failure, logs a SEVERE message with [description]
     * and the causing exception, then disables the plugin so it never runs in a partially
     * initialized state (SS20.4: "do not run in a partially initialized state").
     *
     * @return `true` if [block] completed without throwing, `false` if the plugin was disabled.
     */
    private fun runStartupStep(description: String, block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (e: Exception) {
            logger.log(
                Level.SEVERE,
                "SchemDepot failed to $description during startup; disabling the plugin.",
                e,
            )
            server.pluginManager.disablePlugin(this)
            false
        }
    }

    /**
     * [AssetService]'s `mainThreadDispatcher` (SS12): schedules [runnable] onto the Bukkit main
     * thread via `BukkitScheduler.runTask(Plugin, Runnable)` (verified signature:
     * `org.bukkit.scheduler.BukkitScheduler#runTask(org.bukkit.plugin.Plugin, java.lang.Runnable):
     * org.bukkit.scheduler.BukkitTask`, confirmed against the paper-api artifact via `javap`).
     *
     * `runTask` throws [IllegalPluginAccessException] if the plugin is not enabled at the time of
     * scheduling. Since [AssetService] callbacks can complete after the plugin has started
     * disabling (a worker-thread task in flight during `onDisable`), this checks [isEnabled]
     * first and additionally catches [IllegalPluginAccessException] to close the race window,
     * logging instead of propagating either way.
     */
    private fun dispatchToMainThread(runnable: Runnable) {
        if (!isEnabled) {
            logger.warning("Dropped a main-thread SchemDepot callback because the plugin is disabled.")
            return
        }
        try {
            server.scheduler.runTask(this, runnable)
        } catch (e: IllegalPluginAccessException) {
            logger.log(
                Level.WARNING,
                "Dropped a main-thread SchemDepot callback because the plugin is disabling.",
                e,
            )
        }
    }

    private fun shutdownWorkerExecutor() {
        workerExecutor.shutdown()
        try {
            if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warning("SchemDepot worker executor did not terminate in time; forcing shutdown.")
                workerExecutor.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            workerExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private class WorkerThreadFactory : java.util.concurrent.ThreadFactory {
        private val counter = AtomicInteger(0)

        override fun newThread(runnable: Runnable): Thread {
            val thread = Thread(runnable, "SchemDepot-Worker-${counter.getAndIncrement()}")
            thread.isDaemon = false
            return thread
        }
    }
}
