package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceSelectionFilterTest {
    private val rowId = Uuid.normalized("11111111-1111-1111-1111-111111111111")

    @Test
    fun wifiCapableIsConnectableWithoutBleHandle() {
        val methods = listOf(ConnectionMethod.WiFi("10.0.0.2", 5000, "Home"))
        assertTrue(DeviceSelectionFilter.isConnectable(methods, bleHandle = null))
    }

    @Test
    fun bleWithStoredHandleIsConnectable() {
        val methods = listOf(ConnectionMethod.Bluetooth(rowId, displayName = "AA:BB:CC:DD:EE:FF"))
        val handle = DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = null)
        assertEquals("AA:BB:CC:DD:EE:FF", handle)
        assertTrue(DeviceSelectionFilter.isConnectable(methods, handle))
    }

    @Test
    fun legacyBleRowUsesLastConnectionHandle() {
        val methods = listOf(ConnectionMethod.Bluetooth(rowId, displayName = null))
        val handle = DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = "11:22:33:44:55:66")
        assertEquals("11:22:33:44:55:66", handle)
        assertTrue(DeviceSelectionFilter.isConnectable(methods, handle))
    }

    @Test
    fun backupShadowWithEmptyMethodsIsHidden() {
        val methods = emptyList<ConnectionMethod>()
        val handle = DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = null)
        assertNull(handle)
        assertFalse(DeviceSelectionFilter.isConnectable(methods, handle))
    }

    @Test
    fun bluetoothWithoutHandleIsHidden() {
        val methods = listOf(ConnectionMethod.Bluetooth(rowId, displayName = "  "))
        val handle = DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = null)
        assertNull(handle)
        assertFalse(DeviceSelectionFilter.isConnectable(methods, handle))
    }

    @Test
    fun wifiPlusBlePrefersWifiPrimary() {
        val methods = listOf(
            ConnectionMethod.Bluetooth(rowId, displayName = "AA:BB:CC:DD:EE:FF"),
            ConnectionMethod.WiFi("10.0.0.2", 5000, null),
        )
        assertEquals("10.0.0.2", DeviceSelectionFilter.primaryWifi(methods)?.host)
        assertTrue(DeviceSelectionFilter.isConnectable(methods, bleHandle = null))
    }

    @Test
    fun blankLastHandleIsIgnored() {
        val methods = listOf(ConnectionMethod.Bluetooth(rowId, displayName = null))
        assertNull(DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = " "))
    }

    @Test
    fun lastWifiTransportHandleIsNotABleHandle() {
        val methods = listOf(
            ConnectionMethod.WiFi("10.0.0.2", 5000, null),
            ConnectionMethod.Bluetooth(rowId, displayName = null),
        )
        val last = DeviceSelectionFilter.wifiTransportHandle("10.0.0.2", 5000)
        assertNull(DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = last))
    }

    @Test
    fun bluetoothDisplayNameWinsOverAWifiLastHandle() {
        val methods = listOf(
            ConnectionMethod.WiFi("10.0.0.2", 5000, null),
            ConnectionMethod.Bluetooth(rowId, displayName = "AA:BB:CC:DD:EE:FF"),
        )
        val last = DeviceSelectionFilter.wifiTransportHandle("10.0.0.2", 5000)
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            DeviceSelectionFilter.bleHandle(methods, lastHandleIfThisDevice = last),
        )
    }

    @Test
    fun wifiListDescriptionOmitsDefaultPort() {
        val wifi = ConnectionMethod.WiFi("radio.local", 5000, null)
        assertEquals("radio.local", wifiShortDescription(wifi))
    }

    @Test
    fun wifiListDescriptionIncludesNonDefaultPort() {
        val wifi = ConnectionMethod.WiFi("10.0.0.2", 5001, null)
        assertEquals("10.0.0.2:5001", wifiShortDescription(wifi))
    }

    @Test
    fun elsewhereRequiresDisconnectedAndAMatchingBleHandle() {
        val ble = saved("AA:BB:CC:DD:EE:FF")
        val handles = setOf("aa:bb:cc:dd:ee:ff")
        assertTrue(
            savedDeviceIsConnectedElsewhere(ble, DeviceConnectionState.DISCONNECTED, handles),
        )
        assertFalse(
            savedDeviceIsConnectedElsewhere(ble, DeviceConnectionState.READY, handles),
        )
        assertFalse(
            savedDeviceIsConnectedElsewhere(ble, DeviceConnectionState.DISCONNECTED, emptySet()),
        )
    }

    @Test
    fun otherAppAlertSkipsTheLastConnectedRadio() {
        assertTrue(shouldPresentOtherAppInsteadOfConnect(isHeldByOtherApp = true, isLastConnectedRadio = false))
        assertFalse(shouldPresentOtherAppInsteadOfConnect(isHeldByOtherApp = true, isLastConnectedRadio = true))
        assertFalse(shouldPresentOtherAppInsteadOfConnect(isHeldByOtherApp = false, isLastConnectedRadio = false))
        assertTrue(
            shouldRefuseSavedConnectForOtherApp(
                connectsViaWiFi = false,
                isHeldByOtherApp = true,
                isLastConnectedRadio = false,
            ),
        )
        assertFalse(
            shouldRefuseSavedConnectForOtherApp(
                connectsViaWiFi = true,
                isHeldByOtherApp = true,
                isLastConnectedRadio = false,
            ),
        )
    }

    @Test
    fun inFlightBleConnectJoinsEvenWhenTheUserForcesReconnect() {
        assertTrue(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = false,
                    connectingSameDevice = true,
                    reconnectInProgressForSameDevice = false,
                ),
            ),
        )
        assertTrue(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = false,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = true,
                ),
            ),
        )
        assertTrue(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = true,
                    connectingSameDevice = true,
                    reconnectInProgressForSameDevice = false,
                ),
            ),
        )
        assertTrue(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = true,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = true,
                ),
            ),
        )
        assertFalse(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = false,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = false,
                ),
            ),
        )
        assertFalse(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = true,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = false,
                ),
            ),
        )
    }

    @Test
    fun disconnectedOrBondLostInFlightBleConnectStaysSeparate() {
        assertFalse(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = true,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = true,
                    connectionIsDisconnected = true,
                ),
            ),
        )
        assertTrue(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = true,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = true,
                    connectionIsDisconnected = true,
                    sessionRebuildOwnsHandle = true,
                ),
            ),
        )
        assertFalse(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = true,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = true,
                    connectionIsBondLost = true,
                ),
            ),
        )
        assertFalse(
            shouldJoinInFlightBleConnect(
                InFlightBleJoin(
                    forceReconnect = false,
                    connectingSameDevice = false,
                    reconnectInProgressForSameDevice = true,
                    connectionIsBondLost = true,
                ),
            ),
        )
    }

    @Test
    fun watchdogDefersANonLastRadioHeldByAnotherApp() {
        val handles = setOf("aa:bb:cc:dd:ee:ff")
        assertTrue(
            shouldDeferReconnectBecauseOtherApp(
                handle = "AA:BB:CC:DD:EE:FF",
                lastHandle = "other",
                connectionState = DeviceConnectionState.DISCONNECTED,
                systemConnectedHandles = handles,
            ),
        )
        assertFalse(
            shouldDeferReconnectBecauseOtherApp(
                handle = "AA:BB:CC:DD:EE:FF",
                lastHandle = "AA:BB:CC:DD:EE:FF",
                connectionState = DeviceConnectionState.DISCONNECTED,
                systemConnectedHandles = handles,
            ),
        )
        assertFalse(
            shouldDeferReconnectBecauseOtherApp(
                handle = "AA:BB:CC:DD:EE:FF",
                lastHandle = "other",
                connectionState = DeviceConnectionState.DISCONNECTED,
                systemConnectedHandles = emptySet(),
            ),
        )
    }

    @Test
    fun foregroundDoesNotStealADisconnectedBleWatchdogForWifi() {
        assertFalse(
            shouldRunForegroundWifiOrBleArm(canArmBle = true, bleReconnectInProgress = true),
        )
        assertTrue(
            shouldRunForegroundWifiOrBleArm(canArmBle = true, bleReconnectInProgress = false),
        )
        assertFalse(
            shouldRunForegroundWifiOrBleArm(canArmBle = false, bleReconnectInProgress = false),
        )
    }

    @Test
    fun handleElsewhereIgnoresCaseAndRequiresDisconnected() {
        val handles = setOf("aa:bb:cc:dd:ee:ff")
        assertTrue(
            bleHandleIsHeldByOtherApp("AA:BB:CC:DD:EE:FF", DeviceConnectionState.DISCONNECTED, handles),
        )
        assertFalse(
            bleHandleIsHeldByOtherApp("AA:BB:CC:DD:EE:FF", DeviceConnectionState.READY, handles),
        )
    }

    @Test
    fun systemConnectedHandleAdoptsWithoutAutoConnect() {
        val handles = setOf("aa:bb:cc:dd:ee:ff")
        assertTrue(shouldAdoptSystemConnectedGatt("AA:BB:CC:DD:EE:FF", handles))
        assertFalse(shouldAdoptSystemConnectedGatt("11:22:33:44:55:66", handles))
        assertFalse(shouldAdoptSystemConnectedGatt(null, handles))
        assertFalse(shouldAdoptSystemConnectedGatt("  ", handles))
        assertFalse(shouldAdoptSystemConnectedGatt("aa:bb:cc:dd:ee:ff", emptySet()))
    }

    @Test
    fun idleLastRadioAdoptsWhenSystemConnected() {
        val handle = "AA:BB:CC:DD:EE:FF"
        val handles = setOf("aa:bb:cc:dd:ee:ff")
        assertTrue(
            shouldAdoptLastSystemConnectedWhileIdle(
                IdleSystemGatt(
                    lastHandle = handle,
                    targetHandle = handle,
                    connectionState = ConnectionState.Disconnected,
                    reconnectInProgress = false,
                    linkAlreadyOurs = false,
                    transportIsWifi = false,
                    systemConnectedHandles = handles,
                ),
            ),
        )
        assertTrue(
            shouldAdoptLastSystemConnectedWhileIdle(
                IdleSystemGatt(
                    lastHandle = handle,
                    targetHandle = handle,
                    connectionState = ConnectionState.BondLost(handle),
                    reconnectInProgress = false,
                    linkAlreadyOurs = false,
                    transportIsWifi = false,
                    systemConnectedHandles = handles,
                ),
            ),
        )
        assertFalse(
            shouldAdoptLastSystemConnectedWhileIdle(
                IdleSystemGatt(
                    lastHandle = handle,
                    targetHandle = handle,
                    connectionState = ConnectionState.Connecting(handle),
                    reconnectInProgress = false,
                    linkAlreadyOurs = false,
                    transportIsWifi = false,
                    systemConnectedHandles = handles,
                ),
            ),
        )
        assertFalse(
            shouldAdoptLastSystemConnectedWhileIdle(
                IdleSystemGatt(
                    lastHandle = handle,
                    targetHandle = handle,
                    connectionState = ConnectionState.Disconnected,
                    reconnectInProgress = true,
                    linkAlreadyOurs = false,
                    transportIsWifi = false,
                    systemConnectedHandles = handles,
                ),
            ),
        )
    }

    @Test
    fun idleLastRadioDoesNotAdoptWhenTheLinkIsOursWifiOrAnotherHandle() {
        val handle = "AA:BB:CC:DD:EE:FF"
        val handles = setOf("aa:bb:cc:dd:ee:ff")
        assertFalse(
            shouldAdoptLastSystemConnectedWhileIdle(
                IdleSystemGatt(
                    lastHandle = handle,
                    targetHandle = handle,
                    connectionState = ConnectionState.Disconnected,
                    reconnectInProgress = false,
                    linkAlreadyOurs = true,
                    transportIsWifi = false,
                    systemConnectedHandles = handles,
                ),
            ),
        )
        assertFalse(
            shouldAdoptLastSystemConnectedWhileIdle(
                IdleSystemGatt(
                    lastHandle = handle,
                    targetHandle = handle,
                    connectionState = ConnectionState.Disconnected,
                    reconnectInProgress = false,
                    linkAlreadyOurs = false,
                    transportIsWifi = true,
                    systemConnectedHandles = handles,
                ),
            ),
        )
        assertFalse(
            shouldAdoptLastSystemConnectedWhileIdle(
                IdleSystemGatt(
                    lastHandle = "11:22:33:44:55:66",
                    targetHandle = handle,
                    connectionState = ConnectionState.Disconnected,
                    reconnectInProgress = false,
                    linkAlreadyOurs = false,
                    transportIsWifi = false,
                    systemConnectedHandles = handles,
                ),
            ),
        )
    }

    @Test
    fun missingSessionOrServicesRebuildsALiveGatt() {
        assertTrue(
            shouldRebuildConnectedBleAppStack(
                linkConnected = true,
                hasSession = false,
                hasServices = false,
                reconnectInProgress = false,
                connectCeremonyInFlight = false,
            ),
        )
        assertTrue(
            shouldRebuildConnectedBleAppStack(
                linkConnected = true,
                hasSession = true,
                hasServices = false,
                reconnectInProgress = false,
                connectCeremonyInFlight = false,
            ),
        )
        assertFalse(
            shouldRebuildConnectedBleAppStack(
                linkConnected = true,
                hasSession = true,
                hasServices = true,
                reconnectInProgress = false,
                connectCeremonyInFlight = false,
            ),
        )
        assertFalse(
            shouldRebuildConnectedBleAppStack(
                linkConnected = true,
                hasSession = false,
                hasServices = false,
                reconnectInProgress = true,
                connectCeremonyInFlight = false,
            ),
        )
        assertFalse(
            shouldRebuildConnectedBleAppStack(
                linkConnected = true,
                hasSession = false,
                hasServices = false,
                reconnectInProgress = false,
                connectCeremonyInFlight = true,
            ),
        )
        assertFalse(
            shouldRebuildConnectedBleAppStack(
                linkConnected = false,
                hasSession = false,
                hasServices = false,
                reconnectInProgress = false,
                connectCeremonyInFlight = false,
            ),
        )
    }

    @Test
    fun scanRssiMatchesHandleIgnoringCase() {
        val signals = mapOf("aa:bb:cc:dd:ee:ff" to -62)
        assertEquals(-62, bleScanRssiForHandle("AA:BB:CC:DD:EE:FF", signals))
        assertEquals(-62, bleScanRssiForHandle("aa:bb:cc:dd:ee:ff", signals))
        assertNull(bleScanRssiForHandle("11:22:33:44:55:66", signals))
        assertNull(bleScanRssiForHandle("  ", signals))
        assertNull(bleScanRssiForHandle(null, signals))
    }

    @Test
    fun wifiOnlyRowIsNeverElsewhere() {
        val wifi = SavedRadioDevice(
            id = rowId,
            name = "Home",
            wifi = ConnectionMethod.WiFi("10.0.0.2", 5000, null),
            bleHandle = null,
            lastConnected = 0.0,
        )
        assertFalse(
            savedDeviceIsConnectedElsewhere(
                wifi,
                DeviceConnectionState.DISCONNECTED,
                setOf("AA:BB:CC:DD:EE:FF"),
            ),
        )
    }

    private fun saved(handle: String) = SavedRadioDevice(
        id = rowId,
        name = "Radio",
        wifi = null,
        bleHandle = handle,
        lastConnected = 0.0,
    )
}
