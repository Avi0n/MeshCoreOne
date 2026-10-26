// AndroidOnly: WP-214 Forward active-link policy through the real runtime, radio graph and channel session.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.sync.SyncState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ActiveLinkChannelSyncTest : RoomProcessTest() {
    @Test fun dualMethodEsp32UsesSerialChannelsOnActiveBle() = runTest {
        checkGraph("Heltec V3", DevicePlatform.ESP32, wifi = false, pipeline = false)
    }

    @Test fun dualMethodNrf52UsesPipelinedChannelsOnActiveBle() = runTest {
        checkGraph("T1000-E", DevicePlatform.NRF52, wifi = false, pipeline = true)
    }

    @Test fun dualMethodEsp32UsesPipelinedChannelsOnActiveWifi() = runTest {
        checkGraph("Heltec V3", DevicePlatform.ESP32, wifi = true, pipeline = true)
    }

    @Test fun dualMethodNrf52UsesSerialChannelsOnActiveWifi() = runTest {
        checkGraph("T1000-E", DevicePlatform.NRF52, wifi = true, pipeline = false)
    }

    @Test fun unknownModelStaysUnknownAndSerialOnBle() = runTest {
        checkGraph("Unknown Radio 9000", DevicePlatform.UNKNOWN, wifi = false, pipeline = false)
    }

    @Test fun unknownModelCoalescesToEsp32AndPipelinesOnWifi() = runTest {
        checkGraph("Unknown Radio 9000", DevicePlatform.ESP32, wifi = true, pipeline = true)
    }

    @Test fun reconnectBindsEachNewGraphToItsOwnActiveLink() = runTest {
        val h = harness("T1000-E")
        val target = h.target()
        seedDualMethodDevice(target)
        try {
            h.manager.connect(target)
            assertChannelPath(h, DevicePlatform.NRF52, pipeline = true)
            val first = assertNotNull(h.sessions.current)
            h.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
            h.manager.connect(ConnectionTarget.WiFi("localhost", 5000u))
            assertChannelPath(h, DevicePlatform.NRF52, pipeline = false)
            val second = assertNotNull(h.sessions.current)
            assertNotSame(first, second)
            assertTrue(first.isTornDown)
            assertTrue(second.token.generation.value > first.token.generation.value)
            h.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
            h.manager.connect(target)
            assertChannelPath(h, DevicePlatform.NRF52, pipeline = true)
            assertTrue(second.isTornDown)
            assertEquals(1, h.sessions.outstanding)
        } finally {
            h.container.close()
        }
    }

    private suspend fun TestScope.checkGraph(
        model: String, platform: DevicePlatform, wifi: Boolean, pipeline: Boolean,
    ) {
        val h = harness(model)
        val target = h.target()
        seedDualMethodDevice(target)
        try {
            h.manager.connect(if (wifi) ConnectionTarget.WiFi("localhost", 5000u) else target)
            assertChannelPath(h, platform, pipeline)
            assertTrue(h.manager.connectedDevice!!.connectionMethods.any { it is ConnectionMethod.WiFi })
        } finally {
            h.container.close()
        }
    }

    private fun TestScope.harness(model: String) = ContainerHarness(this, store).also { h ->
        h.container.foreground.activityStarted()
        h.links.configureRadio = { radio ->
            radio.modelName = model
            radio.pipelinedReadsSupported = true
            radio.respond = { command ->
                if (command[0].toInt() == 0x1f) {
                    listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                } else null
            }
        }
    }

    private suspend fun seedDualMethodDevice(target: ConnectionTarget.Bluetooth) {
        store.saveDevice(
            DeviceDTO(
                id = target.deviceId, radioId = RadioId(UUID.randomUUID()), publicKey = key(7),
                nodeName = "Dual method", connectionMethods = listOf(
                    ConnectionMethod.Bluetooth(target.deviceId),
                    ConnectionMethod.WiFi("localhost", 5000u),
                ).snapshot(),
            ),
        )
    }

    private fun assertChannelPath(h: ContainerHarness, platform: DevicePlatform, pipeline: Boolean) {
        h.settle()
        h.assertReady()
        assertEquals(platform, h.manager.detectedPlatform)
        assertEquals(SyncState.Synced, assertNotNull(h.sessions.current).syncCoordinator.state)
        val radio = h.links.radios.last()
        assertEquals((0..7).toList(), radio.sent.filter { it[0].toInt() == 0x1f }.map { it[1].toInt() })
        assertEquals(if (pipeline) 1 else 0, radio.pipelinedReadChecks, "actual MeshCoreSession.getChannels entry")
    }
}
