package io.github.potetogroove.schemdepot

import io.github.potetogroove.schemdepot.config.SchemDepotConfig
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * SchemDepot plugin entry point.
 *
 * Phase 1 (skeleton) responsibilities only: load configuration, prepare the plugin data
 * directory layout, and stand up the worker executor. Domain/database/WorldEdit integration
 * and commands are added in later phases (see docs/SchemDepot_DESIGN.md SS29).
 */
class SchemDepotPlugin : JavaPlugin() {

    /**
     * Bounded worker executor for SQLite/filesystem/schematic I/O.
     * Must never be used to touch Bukkit world/entity/player APIs (SS12 of the design doc).
     */
    lateinit var workerExecutor: ExecutorService
        private set

    private lateinit var schemDepotConfig: SchemDepotConfig

    override fun onEnable() {
        saveDefaultConfig()
        reloadConfig()
        schemDepotConfig = SchemDepotConfig.load(config, logger)

        createDataDirectories(schemDepotConfig)

        workerExecutor = Executors.newFixedThreadPool(2, WorkerThreadFactory())

        logger.info("SchemDepot enabled (data folder: ${dataFolder.absolutePath}).")
    }

    override fun onDisable() {
        if (::workerExecutor.isInitialized) {
            shutdownWorkerExecutor()
        }
        logger.info("SchemDepot disabled.")
    }

    private fun createDataDirectories(schemDepotConfig: SchemDepotConfig) {
        val directories = listOf(
            File(dataFolder, schemDepotConfig.storage.schematicsDirectory),
            File(dataFolder, schemDepotConfig.storage.tempDirectory),
            File(dataFolder, schemDepotConfig.storage.trashDirectory),
        )

        for (directory in directories) {
            if (!directory.exists() && !directory.mkdirs()) {
                logger.severe("Failed to create required directory: ${directory.absolutePath}")
            }
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
