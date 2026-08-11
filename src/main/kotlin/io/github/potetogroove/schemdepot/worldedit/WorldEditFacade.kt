package io.github.potetogroove.schemdepot.worldedit

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.platform.Actor
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player

/**
 * The **single** point of contact between SchemDepot and the WorldEdit API surface
 * (`com.sk89q.worldedit.*`), as required by docs/SchemDepot_DESIGN.md SS15.5 and ADR-005.
 *
 * Nothing outside the `worldedit` package should call [WorldEdit.getInstance], touch a
 * [com.sk89q.worldedit.session.SessionManager], or use [BukkitAdapter]; if the WorldEdit API
 * changes, only this package should have to change.
 *
 * ## FAWE internals are off limits (AC-13)
 * Only `com.sk89q.worldedit.*` and `org.bukkit.*` types are referenced here. FastAsyncWorldEdit
 * supplies the `com.sk89q.worldedit.*` implementation at runtime, but FAWE's own implementation
 * packages are never imported or referenced anywhere in SchemDepot.
 *
 * ## Threading (SS12, AC-12)
 * Every method on this class adapts or touches live Bukkit/WorldEdit session state and MUST be
 * called from the Bukkit **main server thread**. Long-running I/O belongs on the plugin worker
 * executor and is deliberately kept out of this class (see [ClipboardService.writeTo] and
 * [PasteService.loadClipboard], which are the only worker-thread-safe operations in this package).
 *
 * ## Testing
 * This class cannot be unit tested: instantiating it is meaningless without a running server that
 * has WorldEdit/FAWE loaded, and [WorldEdit.getInstance] requires an initialized platform.
 * Verification is deferred to the Phase 7 integration pass (design doc SS29 Phase 7 / SS37).
 */
class WorldEditFacade {

    /**
     * Adapts a Bukkit player to its WorldEdit [Actor] representation.
     *
     * Verified signature: `public static com.sk89q.worldedit.bukkit.BukkitPlayer
     * adapt(org.bukkit.entity.Player)`. The concrete `BukkitPlayer` type is intentionally widened
     * to [Actor] here so callers never depend on a platform implementation class.
     *
     * Main thread only.
     */
    fun actorOf(player: Player): Actor = BukkitAdapter.adapt(player)

    /**
     * Returns the WorldEdit [LocalSession] that owns [actor]'s clipboard, selection and undo
     * history. Creates one if the actor does not have a session yet, exactly like WorldEdit's own
     * commands do.
     *
     * Verified signature: `SessionManager#get(com.sk89q.worldedit.session.SessionOwner)`;
     * [Actor] extends `SessionOwner`.
     *
     * Main thread only.
     */
    fun localSessionOf(actor: Actor): LocalSession =
        WorldEdit.getInstance().sessionManager.get(actor)

    /** Convenience overload of [localSessionOf] for a Bukkit player. Main thread only. */
    fun localSessionOf(player: Player): LocalSession = localSessionOf(actorOf(player))

    /**
     * Adapts a Bukkit world to its WorldEdit representation.
     *
     * Verified signature: `public static com.sk89q.worldedit.world.World adapt(org.bukkit.World)`.
     *
     * Main thread only.
     */
    fun adaptWorld(world: World): com.sk89q.worldedit.world.World = BukkitAdapter.adapt(world)

    /**
     * Converts a Bukkit [Location] to the block coordinates WorldEdit uses for a paste target.
     *
     * Verified signature:
     * `public static com.sk89q.worldedit.math.BlockVector3 asBlockVector(org.bukkit.Location)`.
     *
     * Pure coordinate math on an already-captured [Location]; safe to call from any thread as long
     * as the [Location] itself was snapshotted on the main thread (SS12).
     */
    fun toBlockVector3(location: Location): BlockVector3 = BukkitAdapter.asBlockVector(location)

    /**
     * Creates a new [EditSession] bound to [world] and attributed to [actor].
     *
     * The actor is attached so that WorldEdit/FAWE applies that player's block-change limits and
     * permissions to the edit, and so the edit is attributable in FAWE's own bookkeeping. The
     * caller is responsible for closing the returned session (it is [AutoCloseable]) and for
     * calling [LocalSession.remember] so `//undo` works (SS30-13, AC-06).
     *
     * Implementation note: this uses `WorldEdit#newEditSessionBuilder()` rather than the
     * convenience `newEditSession(World)` overload, because the builder is the only verified way
     * to set an explicit world *and* an actor. (`newEditSession(A extends Actor & Locatable)`
     * derives the world from the actor's current location, which is not necessarily the world we
     * want to paste into.)
     *
     * Verified signatures:
     * - `public com.sk89q.worldedit.EditSessionBuilder newEditSessionBuilder()`
     * - `EditSessionBuilder#world(com.sk89q.worldedit.world.World)`
     * - `EditSessionBuilder#actor(com.sk89q.worldedit.extension.platform.Actor)`
     * - `EditSessionBuilder#build(): com.sk89q.worldedit.EditSession`
     * - `EditSession implements com.sk89q.worldedit.extent.Extent, java.lang.AutoCloseable`
     *
     * Main thread only.
     */
    fun newEditSession(world: com.sk89q.worldedit.world.World, actor: Actor): EditSession =
        WorldEdit.getInstance()
            .newEditSessionBuilder()
            .world(world)
            .actor(actor)
            .build()
}
