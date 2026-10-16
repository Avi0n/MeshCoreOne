package one.meshcore.android

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.battery.OcvEditorState
import one.meshcore.shared.connection.ConnectionSettingsSnapshot
import one.meshcore.shared.settings.DeviceOtherParamsSnapshot
import one.meshcore.shared.settings.LocationSettingsSnapshot
import one.meshcore.shared.settings.NodesAutoAddSnapshot
import one.meshcore.shared.settings.RadioDeviceSnapshot
import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory Device-row snapshots Settings composition reads. Room cannot be queried via
 * `runBlocking` on the main thread — that ANRs the Settings tap. Filled on connect and after writes.
 * Each slot has its own property so a screen can read one field.
 */
internal class DeviceSettingsSnapshotCache {
    private val radioRef = AtomicReference<RadioDeviceSnapshot?>(null)
    private val locationRef = AtomicReference<LocationSettingsSnapshot?>(null)
    private val connectionRef = AtomicReference<ConnectionSettingsSnapshot?>(null)
    private val nodesAutoAddRef = AtomicReference<NodesAutoAddSnapshot?>(null)
    private val otherParamsRef = AtomicReference<DeviceOtherParamsSnapshot?>(null)
    private val ocvRef = AtomicReference<OcvEditorState?>(null)
    private val regionRef = AtomicReference<RegionSelection?>(null)
    private val deviceRowIdRef = AtomicReference<String?>(null)

    var radio: RadioDeviceSnapshot?
        get() = radioRef.get()
        set(value) = radioRef.set(value)

    var location: LocationSettingsSnapshot?
        get() = locationRef.get()
        set(value) = locationRef.set(value)

    var connection: ConnectionSettingsSnapshot?
        get() = connectionRef.get()
        set(value) = connectionRef.set(value)

    var nodesAutoAdd: NodesAutoAddSnapshot?
        get() = nodesAutoAddRef.get()
        set(value) = nodesAutoAddRef.set(value)

    var otherParams: DeviceOtherParamsSnapshot?
        get() = otherParamsRef.get()
        set(value) = otherParamsRef.set(value)

    var ocv: OcvEditorState?
        get() = ocvRef.get()
        set(value) = ocvRef.set(value)

    var region: RegionSelection?
        get() = regionRef.get()
        set(value) = regionRef.set(value)

    var deviceRowId: String?
        get() = deviceRowIdRef.get()
        set(value) = deviceRowIdRef.set(value)

    /** Drops device-row snapshots. The user region stays; it is not owned by the radio. */
    fun clearRadioOwned() {
        radioRef.set(null)
        locationRef.set(null)
        connectionRef.set(null)
        nodesAutoAddRef.set(null)
        otherParamsRef.set(null)
        ocvRef.set(null)
        deviceRowIdRef.set(null)
    }
}
