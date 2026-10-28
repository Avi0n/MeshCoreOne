// AndroidOnly: WP-205 Real Android framework objects with controlled Robolectric callbacks and failures.
package com.meshcoreone.android.core.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import com.meshcoreone.android.core.testing.TestClock
import java.util.UUID
import kotlin.time.Duration
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothGatt

internal class AndroidGattFixture(
    configuration: BleConfiguration = BleConfiguration(),
    addressInput: String = "02:00:00:00:00:01",
) {
    val application: Application = RuntimeEnvironment.getApplication()
    val context: Context = application
    val clock = TestClock()
    val address = addressInput.uppercase(java.util.Locale.ROOT)
    val adapter: BluetoothAdapter
    val device: BluetoothDevice
    val service = BluetoothGattService(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"), BluetoothGattService.SERVICE_TYPE_PRIMARY)
    val tx = BluetoothGattCharacteristic(
        UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"),
        BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE,
    )
    val rx = BluetoothGattCharacteristic(
        UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"),
        BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ,
    )
    val cccd = BluetoothGattDescriptor(
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"),
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
    )
    val gatts = mutableListOf<BluetoothGatt>()
    val gatt: BluetoothGatt get() = gatts.last()
    val shadow: ControlledGattShadow get() = Shadow.extract(gatt)
    var configure: (ControlledGattShadow) -> Unit = {}
    var connectStatus = BluetoothGatt.GATT_SUCCESS
    var immediateConnect = true
    var beforeConnected: ((BluetoothGatt) -> Unit)? = null
    val transport: BleTransport

    init {
        Shadows.shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        ShadowBluetoothAdapter.setIsBluetoothSupported(true)
        adapter = requireNotNull(BluetoothAdapter.getDefaultAdapter())
        Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        device = adapter.getRemoteDevice(address)
        Shadows.shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        rx.addDescriptor(cccd)
        val opened: (BluetoothGatt) -> Unit = { created ->
            gatts.add(created)
            val shadow = Shadow.extract<ControlledGattShadow>(created)
            shadow.addDiscoverableService(service)
            shadow.allowCharacteristicNotification(rx)
            configure(shadow)
            if (immediateConnect) {
                beforeConnected?.invoke(created)
                requireNotNull(shadow.gattCallback).onConnectionStateChange(created, connectStatus, BluetoothProfile.STATE_CONNECTED)
            }
        }
        if (Build.VERSION.SDK_INT >= 37) {
            Shadow.extract<Api37DeviceShadow>(device).onOpen = opened
        } else {
            Shadows.shadowOf(device).setGattConnectionInterceptor { opened(it) }
        }
        transport = BleTransport(AndroidGattFacade(context, BleDeviceHandle(addressInput)), configuration, object : BleClock {
            override val now: Duration get() = clock.now
            override suspend fun sleepUntil(deadline: Duration) = clock.sleepUntil(deadline)
        })
    }

    fun verifyFirmware(commands: Boolean = false, pipelining: Boolean = false) {
        transport.updateFirmwareCapabilities(
            transport.diagnostics.value.generation,
            FirmwareFrameCapabilities(512, commands, pipelining, "controlled framework test peer"),
        )
    }
}

@Implements(BluetoothGatt::class, minSdk = 31)
class ControlledGattShadow : ShadowBluetoothGatt() {
    @RealObject private lateinit var realGatt: BluetoothGatt
    var pause: GattOperationKind? = null
    var reject: GattOperationKind? = null
    var status = BluetoothGatt.GATT_SUCCESS
    var actualMtu = 517
    var returnedRssi = -50
    var rssiStatus = BluetoothGatt.GATT_SUCCESS
    var rejectLocalNotification = false
    val calls = mutableListOf<String>()
    val writes = mutableListOf<ByteArray>()
    val descriptorValues = mutableListOf<ByteArray>()
    val writeTypes = mutableListOf<Int>()
    var disconnectCount = 0
    var closeCount = 0
    var throwOnDisconnect = false
    var throwOnClose = false

    fun completeDiscovery(): Boolean = super.discoverServices()

    @Implementation
    override fun discoverServices(): Boolean {
        calls.add("discover")
        if (reject == GattOperationKind.DiscoverServices) return false
        if (pause == GattOperationKind.DiscoverServices) return true
        if (status != BluetoothGatt.GATT_SUCCESS) {
            requireNotNull(gattCallback).onServicesDiscovered(realGatt, status)
            return true
        }
        return super.discoverServices()
    }

    @Implementation
    override fun requestMtu(mtu: Int): Boolean {
        calls.add("mtu:$mtu")
        if (reject == GattOperationKind.Mtu) return false
        if (pause != GattOperationKind.Mtu) requireNotNull(gattCallback).onMtuChanged(realGatt, actualMtu, status)
        return true
    }

    @Implementation
    override fun readRemoteRssi(): Boolean {
        calls.add("rssi")
        if (reject == GattOperationKind.Rssi) return false
        if (pause != GattOperationKind.Rssi) requireNotNull(gattCallback).onReadRemoteRssi(realGatt, returnedRssi, rssiStatus)
        return true
    }

    @Implementation
    override fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enable: Boolean): Boolean {
        calls.add("notify:$enable")
        if (rejectLocalNotification) return false
        return super.setCharacteristicNotification(characteristic, enable)
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean {
        calls.add("descriptor:legacy")
        descriptorValues.add(requireNotNull(descriptor.value).copyOf())
        if (reject == GattOperationKind.Subscribe) return false
        if (pause != GattOperationKind.Subscribe) requireNotNull(gattCallback).onDescriptorWrite(realGatt, descriptor, status)
        return true
    }

    @Implementation(minSdk = 33)
    override fun writeDescriptor(descriptor: BluetoothGattDescriptor, value: ByteArray): Int {
        calls.add("descriptor:modern")
        descriptorValues.add(value.copyOf())
        if (reject == GattOperationKind.Subscribe) return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
        if (pause != GattOperationKind.Subscribe) requireNotNull(gattCallback).onDescriptorWrite(realGatt, descriptor, status)
        return BluetoothStatusCodes.SUCCESS
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        calls.add("write:legacy")
        writes.add(requireNotNull(characteristic.value).copyOf())
        writeTypes.add(characteristic.writeType)
        if (reject == GattOperationKind.Write) return false
        if (pause != GattOperationKind.Write) requireNotNull(gattCallback).onCharacteristicWrite(realGatt, characteristic, status)
        return true
    }

    @Implementation(minSdk = 33)
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic, value: ByteArray, writeType: Int): Int {
        calls.add("write:modern")
        writes.add(value.copyOf())
        writeTypes.add(writeType)
        if (reject == GattOperationKind.Write) return BluetoothStatusCodes.ERROR_GATT_WRITE_REQUEST_BUSY
        if (pause != GattOperationKind.Write) requireNotNull(gattCallback).onCharacteristicWrite(realGatt, characteristic, status)
        return BluetoothStatusCodes.SUCCESS
    }

    @Implementation
    override fun disconnect() {
        disconnectCount++
        if (throwOnDisconnect) throw SecurityException("Controlled disconnect permission revocation")
        super.disconnect()
    }

    @Implementation
    override fun close() {
        closeCount++
        if (throwOnClose) throw SecurityException("Controlled close permission revocation")
        super.close()
    }
}
