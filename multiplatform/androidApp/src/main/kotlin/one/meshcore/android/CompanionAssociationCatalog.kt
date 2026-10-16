package one.meshcore.android

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.CompanionDeviceManager
import android.content.Intent
import android.os.Build
import android.os.Parcelable
import androidx.annotation.MainThread
import one.meshcore.shared.connection.CompanionAssociateResult
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.companionAssociationRemovals

/** Lists Companion Device Manager associations and reads a chooser result into one. */
@MainThread
internal class CompanionAssociationCatalog(private val state: CompanionRegistryState) {
    fun associations(): List<CompanionAssociation> {
        val cdm = state.manager ?: return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return cdm.myAssociations.mapNotNull { info ->
                val handle = associationHandle(info) ?: return@mapNotNull null
                CompanionAssociation(handle = handle, name = associationName(info, handle))
            }
        }
        @Suppress("DEPRECATION")
        return cdm.associations.map { mac ->
            CompanionAssociation(handle = mac, name = mac)
        }
    }

    fun refresh(): List<CompanionAssociation> {
        val current = associations()
        val removed = companionAssociationRemovals(state.previousAssociations, current)
        state.previousAssociations = current
        if (removed.isNotEmpty()) state.removals.tryEmit(removed)
        return removed
    }

    fun associationFromChooser(data: Intent?): CompanionAssociateResult? {
        if (data == null) return null
        return tiramisuChooserResult(data) ?: deviceChooserResult(data)
    }

    private fun tiramisuChooserResult(data: Intent): CompanionAssociateResult? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val info = data.getParcelableExtra(
            CompanionDeviceManager.EXTRA_ASSOCIATION,
            AssociationInfo::class.java,
        ) ?: return null
        return fromInfo(info)
    }

    private fun deviceChooserResult(data: Intent): CompanionAssociateResult? {
        val device = bluetoothDeviceFromChooser(data) ?: return null
        val handle = device.address
        return when (handle) {
            null -> CompanionAssociateResult.Failed("no bluetooth address")
            else -> CompanionAssociateResult.Associated(
                CompanionAssociation(handle = handle, name = bluetoothDeviceName(device) ?: handle),
            )
        }
    }

    fun fromInfo(info: AssociationInfo): CompanionAssociateResult {
        val handle = associationHandle(info) ?: return CompanionAssociateResult.Failed("no bluetooth address")
        return CompanionAssociateResult.Associated(
            CompanionAssociation(handle = handle, name = associationName(info, handle)),
        )
    }

    fun associationHandle(info: AssociationInfo): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return info.deviceMacAddress?.toString()
        }
        return null
    }

    private fun associationName(info: AssociationInfo, handle: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val display = info.displayName?.toString()?.trim().orEmpty()
            if (display.isNotEmpty()) return display
        }
        return handle
    }

    @Suppress("DEPRECATION")
    private fun bluetoothDeviceFromChooser(data: Intent): BluetoothDevice? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
        return when (val selected = data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)) {
            is BluetoothDevice -> selected
            is ScanResult -> selected.device
            else -> null
        }
    }

    @Suppress("MissingPermission")
    private fun bluetoothDeviceName(device: BluetoothDevice): String? =
        runCatching { device.name?.trim()?.takeIf { it.isNotEmpty() } }.getOrNull()
}
