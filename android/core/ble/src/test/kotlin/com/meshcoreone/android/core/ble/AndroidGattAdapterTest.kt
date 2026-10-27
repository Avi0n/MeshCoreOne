// AndroidOnly: WP-205 Real native adapter scenarios shared by actual SDK31/32/33/37 framework sandboxes.
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@file:Suppress("DEPRECATION")

package com.meshcoreone.android.core.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Intent
import android.os.Build
import android.os.Looper
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.Shadows
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothGatt

abstract class AndroidGattAdapterTest {
    @Test fun `native reconnect bounds every discovery stage without silently rearming`() = runTest {
        for (stage in listOf(GattOperationKind.DiscoverServices, GattOperationKind.Mtu, GattOperationKind.Subscribe)) {
            val fixture = AndroidGattFixture()
            fixture.transport.connect()
            fixture.transport.disconnect()
            fixture.configure = { it.pause = stage }
            val connecting = async {
                assertFailsWith<BleTransportException> { fixture.transport.connect(BleConnectMode.Reconnect) }
            }
            runCurrent()
            fixture.clock.advanceBy(14_999.milliseconds)
            runCurrent()
            assertFalse(connecting.isCompleted, stage.name)
            fixture.clock.advanceBy(1.milliseconds)
            runCurrent()
            val failure = connecting.await()
            assertEquals(BleError.ConnectionTimeout, failure.error)
            assertEquals(stage, failure.operation)
            assertEquals(BleRecovery.RetryWithNewConnection, failure.recovery)
            assertEquals(BlePhase.Idle, fixture.transport.diagnostics.value.phase)
            assertEquals(2L, fixture.transport.diagnostics.value.generation)
            assertEquals(0, fixture.clock.sleeperCount)
            assertTrue(fixture.gatts.all { Shadow.extract<ControlledGattShadow>(it).closeCount == 1 })
            assertFalse(Shadows.shadowOf(fixture.application).hasReceiverForIntent(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)))
            val calls = fixture.shadow.calls.toList()
            fixture.clock.advanceBy((24 * 60 * 60).seconds)
            runCurrent()
            assertEquals(2, fixture.gatts.size)
            assertEquals(calls, fixture.shadow.calls)
        }

        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.transport.disconnect()
        fixture.configure = { it.pause = GattOperationKind.DiscoverServices }
        val connecting = async {
            assertFailsWith<BleTransportException> { fixture.transport.connect(BleConnectMode.Reconnect) }
        }
        runCurrent()
        val gatt = fixture.gatt
        val callback = requireNotNull(fixture.shadow.gattCallback)
        fixture.clock.advanceBy(8.seconds)
        runCurrent()
        fixture.shadow.pause = GattOperationKind.Mtu
        assertTrue(fixture.shadow.discoverServices())
        runCurrent()
        assertTrue(fixture.shadow.calls.any { it.startsWith("mtu:") })
        fixture.clock.advanceBy(4.seconds)
        runCurrent()
        fixture.shadow.pause = GattOperationKind.Subscribe
        callback.onMtuChanged(gatt, fixture.shadow.actualMtu, BluetoothGatt.GATT_SUCCESS)
        runCurrent()
        assertTrue(fixture.shadow.calls.any { it.startsWith("descriptor:") })
        fixture.clock.advanceBy(2_999.milliseconds)
        runCurrent()
        assertFalse(connecting.isCompleted)
        fixture.clock.advanceBy(1.milliseconds)
        runCurrent()
        val failure = connecting.await()
        assertEquals(BleError.ConnectionTimeout, failure.error)
        assertEquals(GattOperationKind.Subscribe, failure.operation)
        assertEquals(BlePhase.Idle, fixture.transport.diagnostics.value.phase)
        assertEquals(2L, fixture.transport.diagnostics.value.generation)
        assertEquals(0, fixture.clock.sleeperCount)
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `cancelled native writes require a fresh GATT and reject old completion callbacks`() = runTest {
        for (command in listOf(false, true)) {
            val fixture = AndroidGattFixture()
            fixture.transport.connect()
            fixture.verifyFirmware(commands = true)
            fixture.shadow.pause = GattOperationKind.Write
            val sending = async {
                if (command) fixture.transport.sendWithoutResponse(Bytes.of(0x16, 0x03))
                else fixture.transport.send(Bytes.of(0x16, 0x03))
            }
            runCurrent()
            val oldGatt = fixture.gatt
            val oldShadow = fixture.shadow
            val oldCallback = requireNotNull(oldShadow.gattCallback)
            assertEquals(1, oldShadow.writes.size)
            assertEquals(
                if (command) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                oldShadow.writeTypes.single(),
            )
            sending.cancelAndJoin()
            assertFailsWith<CancellationException> { sending.await() }
            assertFalse(fixture.transport.isConnected())
            assertEquals(1, oldShadow.closeCount)
            assertEquals(0, fixture.clock.sleeperCount)
            fixture.transport.connect(BleConnectMode.Reconnect)
            assertEquals(2, fixture.gatts.size)
            assertFalse(fixture.transport.supportsWriteWithoutResponse())
            assertEquals(20, fixture.transport.diagnostics.value.maximumCommandBytes)
            fixture.shadow.pause = GattOperationKind.Write
            val replacement = async { fixture.transport.send(Bytes.of(0x16, 0x04)) }
            runCurrent()
            oldCallback.onCharacteristicWrite(oldGatt, fixture.tx, 0)
            oldCallback.onConnectionStateChange(oldGatt, 0, BluetoothProfile.STATE_DISCONNECTED)
            runCurrent()
            assertFalse(replacement.isCompleted)
            assertTrue(fixture.transport.isConnected())
            assertTrue(fixture.transport.diagnostics.value.rejectedCallbacks >= 2)
            requireNotNull(fixture.shadow.gattCallback).onCharacteristicWrite(fixture.gatt, fixture.tx, 0)
            runCurrent()
            replacement.await()
            fixture.transport.disconnect()
            assertTrue(fixture.gatts.all { Shadow.extract<ControlledGattShadow>(it).closeCount == 1 })
        }
    }

    @Test fun `native acknowledged and command writes retain the half second timeout backstop`() = runTest {
        for (command in listOf(false, true)) {
            val fixture = AndroidGattFixture(BleConfiguration(timeouts = BleTimeouts(write = 500.milliseconds)))
            fixture.transport.connect()
            fixture.verifyFirmware(commands = true)
            fixture.shadow.pause = GattOperationKind.Write
            val sending = async {
                assertFailsWith<BleTransportException> {
                    if (command) fixture.transport.sendWithoutResponse(Bytes.of(0x16, 0x03))
                    else fixture.transport.send(Bytes.of(0x16, 0x03))
                }
            }
            runCurrent()
            assertEquals(1, fixture.shadow.writes.size)
            val callback = requireNotNull(fixture.shadow.gattCallback)
            fixture.clock.advanceBy(499.milliseconds)
            runCurrent()
            assertFalse(sending.isCompleted)
            assertEquals(0, fixture.shadow.closeCount)
            fixture.clock.advanceBy(1.milliseconds)
            runCurrent()
            val failure = sending.await()
            assertEquals(BleError.OperationTimeout, failure.error)
            assertEquals(GattOperationKind.Write, failure.operation)
            assertEquals(BleRecovery.RetryWithNewConnection, failure.recovery)
            assertFalse(fixture.transport.isConnected())
            assertEquals(1, fixture.shadow.closeCount)
            assertEquals(0, fixture.clock.sleeperCount)
            callback.onCharacteristicWrite(fixture.gatt, fixture.tx, 0)
            assertFalse(fixture.transport.isConnected())
            assertEquals(1, fixture.gatts.size)
            assertEquals(1, fixture.shadow.closeCount)
        }
    }

    @Test fun `native RSSI refresh never infers a radio identity from a reused address`() = runTest {
        val fixture = AndroidGattFixture()
        val firstRadio = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val secondRadio = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val refreshes = mutableListOf<BondRefresh>()
        fixture.transport.setBondRefreshedHandler { refreshes.add(it) }
        fixture.transport.recordBondVerification(firstRadio, Instant.EPOCH)
        fixture.transport.connect()
        val firstGeneration = fixture.transport.diagnostics.value.generation
        val address = fixture.gatt.device.address
        assertEquals(-50, fixture.transport.readRssi())
        assertEquals(Instant.EPOCH, fixture.transport.bondVerification(firstRadio))
        assertTrue(refreshes.isEmpty())
        fixture.transport.setAppSessionLive(firstGeneration, firstRadio)
        fixture.transport.readRssi()
        val firstRefresh = refreshes.single()
        assertEquals(firstRadio, firstRefresh.radioId)
        assertTrue(fixture.transport.isBondRefreshCurrent(firstRefresh))
        fixture.transport.disconnect()
        assertFalse(fixture.transport.isBondRefreshCurrent(firstRefresh))
        fixture.transport.connect(BleConnectMode.Reconnect)
        assertEquals(address, fixture.gatt.device.address)
        assertEquals(-50, fixture.transport.readRssi())
        assertEquals(1, refreshes.size)
        assertEquals(firstRefresh.verifiedAt, fixture.transport.bondVerification(firstRadio))
        assertEquals(
            BleError.StaleGeneration(firstGeneration, fixture.transport.diagnostics.value.generation),
            assertFailsWith<BleTransportException> {
                fixture.transport.setAppSessionLive(firstGeneration, firstRadio)
            }.error,
        )
        fixture.transport.recordBondVerification(secondRadio, Instant.EPOCH)
        fixture.transport.setAppSessionLive(fixture.transport.diagnostics.value.generation, secondRadio)
        fixture.transport.readRssi()
        assertEquals(listOf(firstRadio, secondRadio), refreshes.map { it.radioId })
        assertEquals(firstRefresh.verifiedAt, fixture.transport.bondVerification(firstRadio))
        assertTrue(fixture.transport.isBondRefreshCurrent(refreshes.last()))
        fixture.transport.disconnect()
    }

    @Test fun `native non authentication write errors preserve status and typed write recovery`() = runTest {
        for (status in listOf(3, 133)) {
            val fixture = AndroidGattFixture()
            fixture.transport.connect()
            fixture.shadow.status = status
            val failure = assertFailsWith<BleTransportException> { fixture.transport.send(Bytes.of(0x16, 0x03)) }
            assertEquals(BleError.WriteError("gatt.status.$status"), failure.error)
            assertEquals(GattOperationKind.Write, failure.operation)
            assertEquals(status, failure.status)
            assertEquals(GattStatusDomain.Att, failure.statusDomain)
            assertEquals(BleRecovery.RetryWithNewConnection, failure.recovery)
            assertFalse(fixture.transport.isConnected())
            assertEquals(1, fixture.shadow.closeCount)
        }
    }

    @Test fun `modern platform entry points reject unsupported actual API levels before invoking them`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        if (Build.VERSION.SDK_INT < 37) {
            assertEquals(BleError.PlatformApiUnavailable(37, Build.VERSION.SDK_INT),
                assertFailsWith<BleTransportException> {
                    PlatformGattApi.connectModern(fixture.context, BleDeviceHandle(fixture.address), object : BluetoothGattCallback() {}) { it.run() }
                }.error)
        }
        if (Build.VERSION.SDK_INT < 33) {
            assertEquals(BleError.PlatformApiUnavailable(33, Build.VERSION.SDK_INT),
                assertFailsWith<BleTransportException> {
                    PlatformGattApi.writeModern(fixture.gatt, fixture.tx, byteArrayOf(1), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                }.error)
            assertEquals(BleError.PlatformApiUnavailable(33, Build.VERSION.SDK_INT),
                assertFailsWith<BleTransportException> {
                    PlatformGattApi.descriptorModern(fixture.gatt, fixture.cccd, byteArrayOf(1, 0))
                }.error)
        }
        assertEquals(1, fixture.gatts.size)
        fixture.transport.disconnect()
    }

    @Test fun `an early wrong GATT cannot become the adopted connection handle`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.beforeConnected = { expected ->
            val wrong = ShadowBluetoothGatt.newInstance(fixture.adapter.getRemoteDevice("02:00:00:00:00:02"))
            val callback = requireNotNull(Shadow.extract<ControlledGattShadow>(expected).gattCallback)
            callback.onConnectionStateChange(wrong, 0, BluetoothProfile.STATE_CONNECTED)
        }
        fixture.transport.connect()
        assertTrue(fixture.transport.isConnected())
        assertEquals(1, fixture.gatts.size)
        assertTrue(fixture.transport.diagnostics.value.rejectedCallbacks >= 1)
        fixture.transport.disconnect()
    }

    @Test fun `real adapter binds a synchronous connect callback only to the returned GATT`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        assertTrue(fixture.transport.isConnected())
        assertEquals(1, fixture.gatts.size)
        val descriptorRoute = if (Build.VERSION.SDK_INT >= 33) "descriptor:modern" else "descriptor:legacy"
        assertEquals(listOf("discover", "mtu:517", "notify:true", descriptorRoute), fixture.shadow.calls)
        assertEquals(listOf(Bytes.of(0x01, 0x00)), fixture.shadow.descriptorValues.map(::Bytes))
        fixture.transport.disconnect()
        assertEquals(1, fixture.shadow.closeCount)
        assertEquals(1, fixture.shadow.disconnectCount)
        assertTrue(fixture.shadow.isClosed)
    }

    @Test fun `real characteristic and descriptor write overloads match the actual API level`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.transport.send(Bytes.of(0x16, 0x03, 0x80, 0xff))
        assertEquals(Bytes.of(0x16, 0x03, 0x80, 0xff), Bytes(fixture.shadow.writes.single()))
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT, fixture.shadow.writeTypes.single())
        assertEquals(if (Build.VERSION.SDK_INT >= 33) "write:modern" else "write:legacy", fixture.shadow.calls.last())
        fixture.transport.disconnect()
    }

    @Test fun `real write command path requires firmware evidence and preserves whole frames`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.verifyFirmware(commands = true)
        fixture.transport.sendWithoutResponse(Bytes(ByteArray(512) { (it and 0xff).toByte() }))
        assertEquals(1, fixture.shadow.writes.size)
        assertEquals(512, fixture.shadow.writes.single().size)
        assertEquals(0xff.toByte(), fixture.shadow.writes.single().last())
        assertEquals(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, fixture.shadow.writeTypes.single())
        fixture.transport.disconnect()
    }

    @Test fun `callback notification bytes are copied at entry across the platform looper queue`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        val callback = requireNotNull(fixture.shadow.gattCallback)
        val bytes = byteArrayOf(0x80.toByte(), 0xff.toByte())
        val worker = thread(start = true) {
            if (Build.VERSION.SDK_INT >= 33) callback.onCharacteristicChanged(fixture.gatt, fixture.rx, bytes)
            else {
                fixture.rx.value = bytes
                callback.onCharacteristicChanged(fixture.gatt, fixture.rx)
            }
            bytes.fill(0)
            fixture.rx.value = byteArrayOf(0)
        }
        worker.join()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(0x80, 0xff)), fixture.transport.receivedData().toList())
    }

    @Test fun `API appropriate notification signature avoids duplicate legacy and modern delivery`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        val callback = requireNotNull(fixture.shadow.gattCallback)
        fixture.rx.value = byteArrayOf(0x01)
        callback.onCharacteristicChanged(fixture.gatt, fixture.rx)
        if (Build.VERSION.SDK_INT >= 33) callback.onCharacteristicChanged(fixture.gatt, fixture.rx, byteArrayOf(0x02))
        fixture.transport.disconnect()
        val expected = if (Build.VERSION.SDK_INT >= 33) Bytes.of(2) else Bytes.of(1)
        assertEquals(listOf(expected), fixture.transport.receivedData().toList())
    }

    @Test fun `real descriptor rejects wrong object even when UUID matches`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.configure = { it.pause = GattOperationKind.Subscribe }
        val connecting = async { fixture.transport.connect() }
        runCurrent()
        val callback = requireNotNull(fixture.shadow.gattCallback)
        val wrong = BluetoothGattDescriptor(NusUuid.CCCD, BluetoothGattDescriptor.PERMISSION_WRITE)
        fixture.rx.addDescriptor(wrong)
        callback.onDescriptorWrite(fixture.gatt, wrong, 0)
        runCurrent()
        assertFalse(connecting.isCompleted)
        callback.onDescriptorWrite(fixture.gatt, fixture.cccd, 0)
        runCurrent()
        connecting.await()
        fixture.transport.disconnect()
    }

    @Test fun `real write rejects wrong GATT characteristic and operation callback kinds`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.shadow.pause = GattOperationKind.Write
        val sending = async { fixture.transport.send(Bytes.of(0x16, 0x03)) }
        runCurrent()
        val callback = requireNotNull(fixture.shadow.gattCallback)
        val wrongGatt = ShadowBluetoothGatt.newInstance(fixture.adapter.getRemoteDevice("02:00:00:00:00:02"))
        callback.onCharacteristicWrite(wrongGatt, fixture.tx, 0)
        callback.onCharacteristicWrite(fixture.gatt,
            BluetoothGattCharacteristic(NusUuid.TX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE), 0)
        callback.onDescriptorWrite(fixture.gatt, fixture.cccd, 0)
        runCurrent()
        assertFalse(sending.isCompleted)
        assertTrue(fixture.transport.diagnostics.value.rejectedCallbacks >= 3)
        callback.onCharacteristicWrite(fixture.gatt, fixture.tx, 0)
        runCurrent()
        sending.await()
        fixture.transport.disconnect()
    }

    @Test fun `immediate write rejection or status failure cannot report success`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.shadow.reject = GattOperationKind.Write
        val failure = assertFailsWith<BleTransportException> { fixture.transport.send(Bytes.of(0x16, 0x03)) }
        assertTrue(failure.error is BleError.GattRejected)
        assertEquals(GattOperationKind.Write, failure.operation)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `local notification rejection is not a successful CCCD subscription`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.configure = { it.rejectLocalNotification = true }
        val failure = assertFailsWith<BleTransportException> { fixture.transport.connect() }
        assertEquals(BleError.GattRejected(GattOperationKind.Subscribe, null), failure.error)
        assertFalse(fixture.transport.isConnected())
        assertTrue(fixture.shadow.calls.none { it.startsWith("descriptor") })
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `descriptor rejection is terminal even if local routing was enabled`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.configure = { it.reject = GattOperationKind.Subscribe }
        val failure = assertFailsWith<BleTransportException> { fixture.transport.connect() }
        assertTrue(failure.error is BleError.GattRejected)
        assertEquals(GattOperationKind.Subscribe, failure.operation)
        assertEquals(1, fixture.shadow.closeCount)
        assertFalse(fixture.transport.isConnected())
    }

    @Test fun `real nonzero authentication callback status preserves typed recovery`() = runTest {
        for (status in listOf(5, 8, 12, 15)) {
            val fixture = AndroidGattFixture()
            fixture.transport.connect()
            fixture.shadow.status = status
            val failure = assertFailsWith<BleTransportException> { fixture.transport.send(Bytes.of(0x16, 0x03)) }
            assertEquals(BleError.AuthenticationFailed, failure.error)
            assertEquals(status, failure.status)
            assertEquals(GattStatusDomain.Att, failure.statusDomain)
            assertEquals(BleRecovery.PairInSystem, failure.recovery)
            assertEquals(1, fixture.shadow.closeCount)
        }
    }

    @Test fun `status133 is a retained GATT failure not guessed pairing or competing app`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.connectStatus = 133
        val failure = assertFailsWith<BleTransportException> { fixture.transport.connect() }
        assertEquals(BleError.ConnectionFailed("gatt.status.133"), failure.error)
        assertEquals(133, failure.status)
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `permission denial is checked before native connectGatt or receiver registration`() = runTest {
        val fixture = AndroidGattFixture()
        Shadows.shadowOf(fixture.application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        assertEquals(BleError.BluetoothUnauthorized, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertTrue(fixture.gatts.isEmpty())
        assertFalse(Shadows.shadowOf(fixture.application).hasReceiverForIntent(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)))
    }

    @Test fun `native adapter absence is a distinct unavailable capability`() {
        val fixture = AndroidGattFixture()
        ShadowBluetoothAdapter.setIsBluetoothSupported(false)
        assertEquals(BluetoothAvailability.Unavailable, PlatformGattApi.availability(fixture.context))
    }

    @Test fun `Bluetooth power broadcast reads actual state and closes the current handle`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        Shadows.shadowOf(fixture.adapter).setState(BluetoothAdapter.STATE_OFF)
        fixture.context.sendBroadcast(Intent(BluetoothAdapter.ACTION_STATE_CHANGED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BleError.BluetoothPoweredOff, fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `permission revocation on a callback is explicit rather than an empty receive stream`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        Shadows.shadowOf(fixture.application).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) {
            requireNotNull(fixture.shadow.gattCallback).onCharacteristicChanged(fixture.gatt, fixture.rx, byteArrayOf(1))
        } else {
            requireNotNull(fixture.shadow.gattCallback).onCharacteristicChanged(fixture.gatt, fixture.rx)
        }
        assertEquals(BleError.BluetoothUnauthorized, fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `bond broadcast never trusts a supplied PIN or peer extras`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.context.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED).putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(fixture.transport.isConnected())
        Shadows.shadowOf(fixture.device).setBondState(BluetoothDevice.BOND_NONE)
        fixture.context.sendBroadcast(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(BleError.AuthenticationFailed, fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `bond required returns system pairing recovery without silently creating a bond`() = runTest {
        val fixture = AndroidGattFixture(BleConfiguration(requireBond = true))
        Shadows.shadowOf(fixture.device).setBondState(BluetoothDevice.BOND_NONE)
        val failure = assertFailsWith<BleTransportException> { fixture.transport.connect() }
        assertEquals(BleError.BondRequired(BondState.None), failure.error)
        assertEquals(BleRecovery.PairInSystem, failure.recovery)
        assertTrue(fixture.gatts.isEmpty())
    }

    @Test fun `real too small MTU is rejected and requested MTU never substitutes for it`() = runTest {
        val fixture = AndroidGattFixture(BleConfiguration(minimumMtu = 247))
        fixture.configure = { it.actualMtu = 185 }
        assertEquals(BleError.MtuTooSmall(185, 247), assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertTrue(fixture.shadow.calls.contains("mtu:517"))
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `native no callback MTU times out and all receiver registrations are removed`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.configure = { it.pause = GattOperationKind.Mtu }
        val connecting = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.clock.advanceBy(40.seconds)
        runCurrent()
        assertEquals(BleError.ConnectionTimeout, connecting.await().error)
        assertEquals(1, fixture.shadow.closeCount)
        assertFalse(Shadows.shadowOf(fixture.application).hasReceiverForIntent(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)))
    }

    @Test fun `service changed invalidates old attributes without any hidden cache refresh`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        requireNotNull(fixture.shadow.gattCallback).onServiceChanged(fixture.gatt)
        assertEquals(BleError.ConnectionFailed("gatt.serviceChanged"), fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `unsolicited MTU change fails closed rather than overstating frame capability`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.verifyFirmware()
        requireNotNull(fixture.shadow.gattCallback).onMtuChanged(fixture.gatt, 23, 0)
        assertEquals(BleError.InvalidResponse, fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.shadow.closeCount)
    }

    @Test fun `real notifications retain equal empty and unknown frames before a receiver attaches`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        val callback = requireNotNull(fixture.shadow.gattCallback)
        for (bytes in listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0x80.toByte()), byteArrayOf(), byteArrayOf(0xff.toByte()))) {
            if (Build.VERSION.SDK_INT >= 33) callback.onCharacteristicChanged(fixture.gatt, fixture.rx, bytes)
            else {
                fixture.rx.value = bytes
                callback.onCharacteristicChanged(fixture.gatt, fixture.rx)
            }
        }
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(0x80), Bytes.of(0x80), Bytes.EMPTY, Bytes.of(0xff)), fixture.transport.receivedData().toList())
    }

    @Test fun `late native callback after reconnect cannot acknowledge the new GATT write`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        val oldGatt = fixture.gatt
        val oldCallback = requireNotNull(fixture.shadow.gattCallback)
        fixture.transport.disconnect()
        fixture.transport.connect()
        fixture.shadow.pause = GattOperationKind.Write
        val sending = async { fixture.transport.send(Bytes.of(0x16, 0x03)) }
        runCurrent()
        oldCallback.onCharacteristicWrite(oldGatt, fixture.tx, 0)
        runCurrent()
        assertFalse(sending.isCompleted)
        requireNotNull(fixture.shadow.gattCallback).onCharacteristicWrite(fixture.gatt, fixture.tx, 0)
        runCurrent()
        sending.await()
        fixture.transport.disconnect()
        assertTrue(fixture.gatts.all { Shadow.extract<ControlledGattShadow>(it).closeCount == 1 })
    }

    @Test fun `native cleanup attempts close after disconnect permission failure and surfaces both causes`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        fixture.shadow.throwOnDisconnect = true
        fixture.shadow.throwOnClose = true
        val failure = assertFailsWith<BleTransportException> { fixture.transport.disconnect() }
        assertEquals(BleError.CleanupFailed("gatt.disconnect"), failure.error)
        assertTrue(failure.cause is SecurityException)
        assertTrue(failure.suppressed.any {
            it is BleTransportException && it.error == BleError.CleanupFailed("gatt.close") && it.cause is SecurityException
        })
        assertEquals(1, fixture.shadow.disconnectCount)
        assertEquals(1, fixture.shadow.closeCount)
        assertFalse(Shadows.shadowOf(fixture.application).hasReceiverForIntent(Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)))
        assertFailsWith<BleTransportException> { fixture.transport.connect() }
        assertEquals(1, fixture.gatts.size)
    }

    @Test fun `real RSSI callback retains signed dBm and reports failure without treating it as bond proof`() = runTest {
        val fixture = AndroidGattFixture()
        fixture.transport.connect()
        assertEquals(-50, fixture.transport.readRssi())
        fixture.shadow.rssiStatus = 133
        assertEquals(BleError.RssiReadFailed(133),
            assertFailsWith<BleTransportException> { fixture.transport.readRssi() }.error)
        assertTrue(fixture.transport.isConnected())
        assertFalse(fixture.transport.diagnostics.value.firmwareVerified)
        assertEquals(0, fixture.shadow.closeCount)
        fixture.transport.disconnect()
    }

    @Test fun `mixed case address uses canonical uppercase in actual Android device lookup`() = runTest {
        val fixture = AndroidGattFixture(addressInput = "aa:bB:cC:dD:ee:Ff")
        fixture.transport.connect()
        assertEquals("AA:BB:CC:DD:EE:FF", fixture.gatt.device.address)
        assertEquals("AA:BB:CC:DD:EE:FF", fixture.transport.diagnostics.value.handle?.address)
        assertTrue(fixture.transport.isConnected())
        fixture.transport.disconnect()
    }

    @Test fun `connection status eight is HCI timeout with or without an active write`() = runTest {
        for (pendingWrite in listOf(false, true)) {
            val fixture = AndroidGattFixture()
            fixture.transport.connect()
            val operation = if (pendingWrite) {
                fixture.shadow.pause = GattOperationKind.Write
                async { assertFailsWith<BleTransportException> { fixture.transport.send(Bytes.of(0x16, 0x03)) } }
            } else null
            runCurrent()
            requireNotNull(fixture.shadow.gattCallback).onConnectionStateChange(fixture.gatt, 8, BluetoothProfile.STATE_DISCONNECTED)
            runCurrent()
            val failure = operation?.await() ?: assertFailsWith<BleTransportException> { fixture.transport.receivedData().toList() }
            assertEquals(BleError.ConnectionTimeout, failure.error)
            assertEquals(GattStatusDomain.ConnectionState, failure.statusDomain)
            assertEquals(8, failure.status)
            assertEquals(if (pendingWrite) GattOperationKind.Write else GattOperationKind.Connect, failure.operation)
            assertEquals(BleRecovery.RetryWithNewConnection, failure.recovery)
            assertEquals(1, fixture.shadow.closeCount)
        }
    }
}
