package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.FakeMeshSession.RecordedFloodScope
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.model.ChannelFloodScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The `ResolvedFloodScope` -> session push mapping and the channel-open dedup latch, driven through a
 * recording [FakeMeshSession]. The bridge freezes the wire frames (Swift + Kotlin goldens); this proves the
 * app layer selects the right `setFloodScope` / `setFloodScopeUnscoped` entry point and dedups repeats.
 */
class FloodScopePushTest {
    @Test
    fun pushMapsUnscopedToSubcommandOne() = runTest {
        val session = FakeMeshSession()
        FloodScopePush.push(session, ResolvedFloodScope.Unscoped) {}
        assertEquals(listOf(RecordedFloodScope.UNSCOPED), session.floodScopeCalls)
    }

    @Test
    fun pushMapsDisabledToDisabledScopeType() = runTest {
        val session = FakeMeshSession()
        FloodScopePush.push(session, ResolvedFloodScope.Scope(FloodScope.Disabled)) {}
        assertEquals(listOf(RecordedFloodScope("disabled", "")), session.floodScopeCalls)
    }

    @Test
    fun pushMapsRegionToRegionScopeTypeWithName() = runTest {
        val session = FakeMeshSession()
        FloodScopePush.push(session, ResolvedFloodScope.Scope(FloodScope.Region("Europe"))) {}
        assertEquals(listOf(RecordedFloodScope("region", "Europe")), session.floodScopeCalls)
    }

    @Test
    fun syncResolvesInheritToDeviceDefaultRegion() = runTest {
        val session = FakeMeshSession()
        ChannelFloodScopeSync().sync(
            session = session,
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "Europe",
            supportsUnscopedFloodSend = true,
        )
        assertEquals(listOf(RecordedFloodScope("region", "Europe")), session.floodScopeCalls)
    }

    @Test
    fun syncResolvesAllRegionsToUnscopedWhenSupported() = runTest {
        val session = FakeMeshSession()
        ChannelFloodScopeSync().sync(
            session = session,
            channelFloodScope = ChannelFloodScope.AllRegions,
            deviceDefaultFloodScopeName = null,
            supportsUnscopedFloodSend = true,
        )
        assertEquals(listOf(RecordedFloodScope.UNSCOPED), session.floodScopeCalls)
    }

    @Test
    fun syncDedupsUnchangedScopeAndRepushesOnChange() = runTest {
        val session = FakeMeshSession()
        val sync = ChannelFloodScopeSync()

        sync.syncRegion(session, "Europe")
        sync.syncRegion(session, "Europe")
        assertEquals(1, session.floodScopeCalls.size)

        // A changed device default (same per-channel preference) re-pushes, since inherit-resolution depends on it.
        sync.sync(
            session = session,
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "US",
            supportsUnscopedFloodSend = false,
        )
        assertEquals(2, session.floodScopeCalls.size)
        assertEquals(RecordedFloodScope("region", "US"), session.floodScopeCalls.last())
    }

    @Test
    fun syncRepushesInheritWhenDeviceDefaultArrivesAfterNull() = runTest {
        val session = FakeMeshSession()
        val sync = ChannelFloodScopeSync()
        sync.sync(
            session = session,
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = null,
            supportsUnscopedFloodSend = false,
        )
        assertEquals(listOf(RecordedFloodScope("disabled", "")), session.floodScopeCalls)
        sync.sync(
            session = session,
            channelFloodScope = ChannelFloodScope.Inherit,
            deviceDefaultFloodScopeName = "Europe",
            supportsUnscopedFloodSend = false,
        )
        assertEquals(RecordedFloodScope("region", "Europe"), session.floodScopeCalls.last())
        assertEquals(2, session.floodScopeCalls.size)
    }

    @Test
    fun syncLeavesTheLatchStaleWhenThePushFails() = runTest {
        val sync = ChannelFloodScopeSync()
        val warnings = mutableListOf<String>()
        sync.syncRegion(failingFloodSession { error("radio busy") }, "Europe", warnings::add)
        assertEquals(listOf("Flood scope push failed: radio busy"), warnings)

        val healthy = FakeMeshSession()
        sync.syncRegion(healthy, "Europe")
        assertEquals(listOf(RecordedFloodScope("region", "Europe")), healthy.floodScopeCalls)
    }

    @Test
    fun syncPropagatesCancellationAndLeavesTheLatchStale() = runTest {
        val sync = ChannelFloodScopeSync()
        val warnings = mutableListOf<String>()
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            sync.syncRegion(failingFloodSession { throw cancelled }, "Europe", warnings::add)
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())

        val healthy = FakeMeshSession()
        sync.syncRegion(healthy, "Europe")
        assertEquals(listOf(RecordedFloodScope("region", "Europe")), healthy.floodScopeCalls)
    }

    @Test
    fun syncPropagatesAnErrorAndLeavesTheLatchStale() = runTest {
        val sync = ChannelFloodScopeSync()
        val warnings = mutableListOf<String>()
        val probe = FloodPushProbeError()
        val thrown = assertFailsWith<FloodPushProbeError> {
            sync.syncRegion(failingFloodSession { throw probe }, "Europe", warnings::add)
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())

        val healthy = FakeMeshSession()
        sync.syncRegion(healthy, "Europe")
        assertEquals(listOf(RecordedFloodScope("region", "Europe")), healthy.floodScopeCalls)
    }

    @Test
    fun resetClearsLatchSoNextSyncRepushes() = runTest {
        val session = FakeMeshSession()
        val sync = ChannelFloodScopeSync()
        sync.syncRegion(session, "Europe")
        sync.reset()
        sync.syncRegion(session, "Europe")
        assertTrue(session.floodScopeCalls.size == 2)
    }

    /** A region-scope sync with no device default and no unscoped support — the common latch-test shape. */
    private suspend fun ChannelFloodScopeSync.syncRegion(
        session: MeshSession,
        name: String,
        warning: (String) -> Unit = {},
    ) = sync(
        session = session,
        channelFloodScope = ChannelFloodScope.Region(name),
        deviceDefaultFloodScopeName = null,
        supportsUnscopedFloodSend = false,
        warning = warning,
    )

    private fun failingFloodSession(fail: () -> Nothing): MeshSession {
        val delegate = FakeMeshSession()
        return object : MeshSession by delegate {
            override suspend fun setFloodScope(scopeType: String, scopeText: String, scopeKey: ByteArray) {
                fail()
            }
        }
    }
}

private class FloodPushProbeError : Error("probe")
