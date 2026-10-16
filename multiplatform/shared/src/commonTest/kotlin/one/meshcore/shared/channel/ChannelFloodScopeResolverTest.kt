package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors the iOS `ChannelFloodScopeResolverTests`: the six resolver rows, byte-for-byte. */
class ChannelFloodScopeResolverTest {
    @Test
    fun inheritWithDeviceDefaultResolvesToRegionOfDefault() {
        val resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "Germany",
            supportsUnscopedFloodSend = true,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("Germany")), resolved)
    }

    @Test
    fun inheritWithNoDeviceDefaultResolvesToDisabled() {
        val resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = null,
            supportsUnscopedFloodSend = true,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolved)
    }

    @Test
    fun inheritWithEmptyDefaultTreatsItAsNoDefault() {
        val resolved = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "",
            supportsUnscopedFloodSend = true,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), resolved)
    }

    @Test
    fun allRegionsOnV12PlusResolvesToUnscoped() {
        val withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.AllRegions,
            deviceDefaultFloodScopeName = "Germany",
            supportsUnscopedFloodSend = true,
        )
        val withoutDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.AllRegions,
            deviceDefaultFloodScopeName = null,
            supportsUnscopedFloodSend = true,
        )
        assertEquals(ResolvedFloodScope.Unscoped, withDefault)
        assertEquals(ResolvedFloodScope.Unscoped, withoutDefault)
    }

    @Test
    fun allRegionsOnOlderFirmwareFallsBackToDisabled() {
        val withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.AllRegions,
            deviceDefaultFloodScopeName = "Germany",
            supportsUnscopedFloodSend = false,
        )
        val withoutDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.AllRegions,
            deviceDefaultFloodScopeName = null,
            supportsUnscopedFloodSend = false,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), withDefault)
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Disabled), withoutDefault)
    }

    @Test
    fun regionResolvesToThatRegionRegardlessOfDefaultOrCapability() {
        val withDefault = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Region("France"),
            deviceDefaultFloodScopeName = "Germany",
            supportsUnscopedFloodSend = true,
        )
        val withoutCapability = ChannelFloodScopeResolver.resolve(
            channelFloodScope = ChannelFloodScope.Region("France"),
            deviceDefaultFloodScopeName = null,
            supportsUnscopedFloodSend = false,
        )
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("France")), withDefault)
        assertEquals(ResolvedFloodScope.Scope(FloodScope.Region("France")), withoutCapability)
    }
}
