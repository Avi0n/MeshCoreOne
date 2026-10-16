package one.meshcore.shared.channel

import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.runLoggedException

/**
 * Maps a [ResolvedFloodScope] onto the session's session-scoped flood-key entry points. The resolver only
 * emits [FloodScope.Disabled] and [FloodScope.Region], so those two `setFloodScope` shapes plus the
 * [ResolvedFloodScope.Unscoped] override are the whole surface; `channelName` / `rawKey` stay unmapped,
 * matching the resolver's own note. The primitive `scopeType` / `scopeText` / `scopeKey` arguments mirror the
 * Swift `MeshCoreBridge.setFloodScope`; the bytes never touch Kotlin, the bridge builds the frame.
 */
object FloodScopePush {
    /**
     * Returns whether the radio accepted the scope. A session fault is logged and returns false so
     * the caller can leave a dedup latch stale. Cancellation and an error still propagate.
     */
    suspend fun push(session: MeshSession, resolved: ResolvedFloodScope, warning: (String) -> Unit): Boolean =
        runLoggedException(warning, FLOOD_SCOPE_PUSH_FAILED, fallback = { false }) {
            when (resolved) {
                ResolvedFloodScope.Unscoped -> session.setFloodScopeUnscoped()
                is ResolvedFloodScope.Scope -> when (val scope = resolved.scope) {
                    FloodScope.Disabled -> session.setFloodScope("disabled", "", ByteArray(0))
                    is FloodScope.Region -> session.setFloodScope("region", scope.name, ByteArray(0))
                }
            }
            true
        }
}

private const val FLOOD_SCOPE_PUSH_FAILED = "Flood scope push failed"

/**
 * Pushes a channel's effective flood scope to the connected radio at channel-open, deduped so an unchanged
 * scope re-issues no command. Kotlin port of the iOS `ChatViewModel.syncFloodScope` plus its `RegionScopeState`
 * latch: the scope is resolved from the per-channel preference, the device default, and the unscoped-send
 * capability, then pushed through [FloodScopePush]. A superseding open cancels the coroutine; the resulting
 * [CancellationException] propagates (structured-concurrency benign) and leaves the latch untouched, so the
 * next open pushes afresh. A [reset] clears the latch on a conversation switch, mirroring the iOS reset.
 */
class ChannelFloodScopeSync {
    private data class PushedState(val scope: ChannelFloodScope, val deviceDefault: String?)

    private var lastPushed: PushedState? = null

    /** Clears the dedup latch so the next [sync] pushes unconditionally. */
    fun reset() {
        lastPushed = null
    }

    suspend fun sync(
        session: MeshSession,
        channelFloodScope: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?,
        supportsUnscopedFloodSend: Boolean,
        warning: (String) -> Unit = {},
    ) {
        val desired = PushedState(channelFloodScope, deviceDefaultFloodScopeName)
        if (lastPushed == desired) return
        val resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope = channelFloodScope,
            deviceDefaultFloodScopeName = deviceDefaultFloodScopeName,
            supportsUnscopedFloodSend = supportsUnscopedFloodSend,
        )
        // Cancellation from a superseding open is benign; let it propagate and keep the latch stale so the
        // next open retries. Every send independently reapplies the saved scope before transmitting.
        if (FloodScopePush.push(session, resolved, warning)) {
            lastPushed = desired
        }
    }
}
