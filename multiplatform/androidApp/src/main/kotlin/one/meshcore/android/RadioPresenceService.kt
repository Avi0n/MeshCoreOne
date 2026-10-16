package one.meshcore.android

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Companion Device Manager presence callbacks. When an associated radio appears, start
 * [ConnectionService] so activate restores the last wanted link, matching iOS
 * `bluetooth-central` relaunch after process death.
 */
@RequiresApi(Build.VERSION_CODES.S)
class RadioPresenceService : CompanionDeviceService() {
    private val graph get() = (application as MeshCoreApplication).container

    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(address: String) {
        graph.onCompanionDeviceAppeared(address)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        graph.onCompanionDeviceAppeared(associationInfo.deviceMacAddress?.toString())
    }
}
