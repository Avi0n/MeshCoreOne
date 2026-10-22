// AndroidOnly: WP-501 Actual BLE link/transport consumers of the frozen post-query platform pacing policy.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.BleRuntimeLink
import com.meshcoreone.android.core.ble.BleClock
import com.meshcoreone.android.core.ble.BleConfiguration
import com.meshcoreone.android.core.ble.BleDeviceHandle
import com.meshcoreone.android.core.ble.BleTransport
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.ble.BondState
import com.meshcoreone.android.core.ble.GattCharacteristic
import com.meshcoreone.android.core.ble.GattConnection
import com.meshcoreone.android.core.ble.GattDescriptor
import com.meshcoreone.android.core.ble.GattEvents
import com.meshcoreone.android.core.ble.GattFacade
import com.meshcoreone.android.core.ble.GattOperation
import com.meshcoreone.android.core.ble.GattProperty
import com.meshcoreone.android.core.ble.GattReply
import com.meshcoreone.android.core.ble.GattService
import com.meshcoreone.android.core.ble.GattWriteMode
import com.meshcoreone.android.core.ble.NusUuid
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.testing.TestClock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Test

class BlePacingAcceptanceTest {
    @Test fun configuredEsp32LinkPacesAcknowledgedWritesForSixtyMilliseconds() =
        assertPacing("Heltec V3", DevicePlatform.ESP32, 60)

    @Test fun configuredNrf52LinkPacesAcknowledgedWritesForTwentyFiveMilliseconds() =
        assertPacing("T-Echo", DevicePlatform.NRF52, 25)

    @Test fun configuredUnknownBluetoothLinkKeepsConservativeSixtyMillisecondPacing() =
        assertPacing("SomeNewDevice XYZ", DevicePlatform.UNKNOWN, 60)

    @Test fun explicitPacingControlProvesAllThreeIndependentClockDeadlines() {
        listOf(
            Triple("Heltec V3", DevicePlatform.ESP32, 60),
            Triple("T-Echo", DevicePlatform.NRF52, 25),
            Triple("SomeNewDevice XYZ", DevicePlatform.UNKNOWN, 60),
        ).forEach { (model, platform, milliseconds) ->
            assertPacing(model, platform, milliseconds, BleConfiguration(writePacing = milliseconds.milliseconds))
        }
    }

    private fun assertPacing(
        model: String,
        expectedPlatform: DevicePlatform,
        milliseconds: Int,
        configuration: BleConfiguration = BleConfiguration(),
    ) = runBlocking<Unit> {
        val clock = TestClock()
        val facade = CompletingGatt(clock)
        val ble = BleTransport(facade, configuration, clock = object : BleClock {
            override val now: Duration get() = clock.now
            override suspend fun sleepUntil(deadline: Duration) = clock.sleepUntil(deadline)
        })
        val link = BleRuntimeLink(ble, UUID.fromString("00000000-0000-0000-0000-000000000001"))
        try {
            link.transport.connect()
            val platform = DevicePlatform.detect(model)
            assertEquals(expectedPlatform, platform)
            link.configure(DeviceCapabilities(9u, 100, 8, 123456u, "b", model, "1.0"), platform)
            link.transport.send(Bytes.of(1))
            assertEquals(listOf(Duration.ZERO), facade.writeTimes)
            val second = async(start = CoroutineStart.UNDISPATCHED) { link.transport.send(Bytes.of(2)) }
            assertFalse(second.isCompleted, "post-query acknowledged writes must not bypass platform pacing")
            clock.advanceBy((milliseconds - 1).milliseconds)
            yield()
            assertFalse(second.isCompleted, "the full source pacing interval is required")
            assertEquals(listOf(Duration.ZERO), facade.writeTimes)
            clock.advanceBy(1.milliseconds)
            yield()
            withTimeout(2_000) { second.await() }
            assertEquals(listOf(Duration.ZERO, milliseconds.milliseconds), facade.writeTimes)
            assertEquals(listOf(GattWriteMode.WithResponse, GattWriteMode.WithResponse), facade.writeModes)
        } finally {
            try { link.transport.disconnect() } finally { link.release() }
        }
    }

    private class CompletingGatt(private val clock: TestClock) : GattFacade {
        override val handle = BleDeviceHandle("AA:BB:CC:DD:EE:FF")
        val writeTimes = mutableListOf<Duration>()
        val writeModes = mutableListOf<GattWriteMode>()
        private val service = GattService(NusUuid.SERVICE, listOf(
            GattCharacteristic(NusUuid.TX, setOf(GattProperty.Write), emptyList()),
            GattCharacteristic(NusUuid.RX, setOf(GattProperty.Notify), listOf(GattDescriptor(NusUuid.CCCD))),
        ))

        override fun availability() = BluetoothAvailability.Ready
        override fun create(events: GattEvents): GattConnection = object : GattConnection {
            override fun submit(operation: GattOperation) {
                val reply = when (operation) {
                    is GattOperation.Connect -> GattReply.Connected(BondState.Bonded)
                    is GattOperation.DiscoverServices -> GattReply.Services(listOf(service))
                    is GattOperation.Mtu -> GattReply.Mtu(517)
                    is GattOperation.Subscribe -> GattReply.Subscribed
                    is GattOperation.Write -> {
                        writeTimes += clock.now
                        writeModes += operation.mode
                        GattReply.Written
                    }
                    is GattOperation.Rssi -> GattReply.Rssi(-50, 0)
                }
                events.onReply(this, operation.key, reply)
            }

            override fun close(): Deferred<Unit> = CompletableDeferred(Unit)
        }
    }
}
