package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope

/**
 * A concrete flood scope pushed to the radio at send time. The resolver-facing subset of the Swift
 * MeshCore `FloodScope`: [Disabled] resets the session scope (zero key) and lets the device fall back to
 * its persisted default; [Region] pins a named region. The remaining Swift cases (`channelName`, `rawKey`)
 * belong to the session-push slice and are added when `setFloodScope` is wired.
 */
sealed interface FloodScope {
    data object Disabled : FloodScope

    data class Region(val name: String) : FloodScope
}

/**
 * The flood-scope action [ChannelFloodScopeResolver] resolves for a conversation. Mirrors the Swift
 * `ResolvedFloodScope`: [Unscoped] is a true override (firmware sub-command 1, requires v12+); [Scope]
 * pushes a concrete [FloodScope]. A zero-key [FloodScope.Disabled] cannot stand in for [Unscoped] because
 * it only resets to the device default rather than overriding it.
 */
sealed interface ResolvedFloodScope {
    data object Unscoped : ResolvedFloodScope

    data class Scope(val scope: FloodScope) : ResolvedFloodScope
}

/**
 * Resolves the [ResolvedFloodScope] to push for a conversation, combining the per-channel
 * [ChannelFloodScope] preference with the device-wide default flood-scope name and the device's
 * unscoped-send capability. Byte-for-byte port of the Swift `ChannelFloodScopeResolver`:
 *
 * - [ChannelFloodScope.Inherit] + a non-empty device default → `Scope(Region(default))`
 * - [ChannelFloodScope.Inherit] + no/empty device default → `Scope(Disabled)`
 * - [ChannelFloodScope.AllRegions] + device supports unscoped send → [ResolvedFloodScope.Unscoped]
 * - [ChannelFloodScope.AllRegions] + device does not → `Scope(Disabled)` (best-effort; old firmware
 *   cannot override its default)
 * - [ChannelFloodScope.Region] → `Scope(Region(name))` regardless of default or capability
 */
object ChannelFloodScopeResolver {
    fun resolve(
        channelFloodScope: ChannelFloodScope,
        deviceDefaultFloodScopeName: String?,
        supportsUnscopedFloodSend: Boolean,
    ): ResolvedFloodScope = when (channelFloodScope) {
        ChannelFloodScope.Inherit ->
            if (!deviceDefaultFloodScopeName.isNullOrEmpty()) {
                ResolvedFloodScope.Scope(FloodScope.Region(deviceDefaultFloodScopeName))
            } else {
                ResolvedFloodScope.Scope(FloodScope.Disabled)
            }

        ChannelFloodScope.AllRegions ->
            if (supportsUnscopedFloodSend) {
                ResolvedFloodScope.Unscoped
            } else {
                ResolvedFloodScope.Scope(FloodScope.Disabled)
            }

        is ChannelFloodScope.Region -> ResolvedFloodScope.Scope(FloodScope.Region(channelFloodScope.name))
    }
}
