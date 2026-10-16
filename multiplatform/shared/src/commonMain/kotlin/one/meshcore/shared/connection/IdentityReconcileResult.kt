package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity

/**
 * Outcome of reconciling a live Device row after `importPrivateKey` (or any identity rotation).
 * Ports the core of Swift `PersistenceStore.reconcileGhostIdentity` + public-key rewrite.
 */
data class IdentityReconcileResult(
    /** Partition key subsequent channel/contact writes must use. */
    val radioID: Uuid,
    /** Device row to upsert (updated public key and possibly radioID). Null when nothing to write. */
    val upsert: DeviceEntity?,
    /** Ghost row id to delete after adopting its partition, if any. */
    val deleteDeviceId: Uuid? = null,
)

/**
 * Reconcile the live device after the radio identity changes.
 *
 * - Prefer an inactive non-BLE ghost whose `publicKey` matches [newPublicKey]: adopt its `radioID`
 *   so restored partitions reattach without orphaning child rows.
 * - Otherwise rewrite the current row's `publicKey` (and name) while keeping its `radioID`.
 *
 * Pure: no Room access — caller applies [IdentityReconcileResult].
 */
fun reconcileDeviceIdentity(
    devices: List<DeviceEntity>,
    currentDeviceId: Uuid,
    currentRadioId: Uuid,
    newPublicKey: ByteArray,
    newNodeName: String,
): IdentityReconcileResult {
    val current = devices.firstOrNull { it.id == currentDeviceId }
        ?: devices.firstOrNull { it.radioID == currentRadioId }
        ?: return IdentityReconcileResult(radioID = currentRadioId, upsert = null)

    val ghost = devices.firstOrNull { device ->
        device.publicKey.contentEquals(newPublicKey) &&
            device.id != currentDeviceId &&
            !device.isActive &&
            device.connectionMethods.none { it is ConnectionMethod.Bluetooth }
    }

    val mergedMethods = ghost?.let { mergeConnectionMethods(current.connectionMethods, it.connectionMethods) }
    return when {
        ghost != null && mergedMethods != null -> IdentityReconcileResult(
            radioID = ghost.radioID,
            upsert = current.copy(
                radioID = ghost.radioID,
                publicKey = newPublicKey.copyOf(),
                nodeName = newNodeName,
                connectionMethods = mergedMethods,
            ),
            deleteDeviceId = ghost.id,
        )
        current.publicKey.contentEquals(newPublicKey) && current.nodeName == newNodeName ->
            IdentityReconcileResult(radioID = current.radioID, upsert = null)
        else -> IdentityReconcileResult(
            radioID = current.radioID,
            upsert = current.copy(
                publicKey = newPublicKey.copyOf(),
                nodeName = newNodeName,
            ),
        )
    }
}
