package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.room.setSelfPublicKeyPrefix

/** Applies a private-key import to the device row and the live partition. */
internal class IdentityReconcile(private val host: ServiceContainer) {
    suspend fun apply(info: SelfInfo, currentDeviceId: Uuid?): Uuid? {
        val devices = readDevicesForReconcile(
            listAll = { host.database.deviceDao().listAll() },
            logger = host.connectionLogger,
        ) ?: return null
        val deviceId = currentDeviceId
            ?: devices.firstOrNull { it.radioID == host.radioId }?.id
            ?: return null
        val previousRadioId = host.radioId
        val previousSelfKey = host.selfPublicKey.copyOf()
        return persist(info, devices, deviceId, previousRadioId, previousSelfKey)
    }

    private suspend fun persist(
        info: SelfInfo,
        devices: List<DeviceEntity>,
        deviceId: Uuid,
        previousRadioId: Uuid,
        previousSelfKey: ByteArray,
    ): Uuid? {
        val result = reconcileDeviceIdentity(
            devices = devices,
            currentDeviceId = deviceId,
            currentRadioId = host.radioId,
            newPublicKey = info.publicKey,
            newNodeName = info.name,
        )
        val persisted = applyGhostReconcilePersist(
            apply = {
                result.upsert?.let { host.database.deviceDao().upsert(it) }
                result.deleteDeviceId?.let { host.database.deviceDao().deleteById(it) }
                host.lastConnectionStore?.let { store ->
                    val handle = store.handle()
                    if (handle != null) store.persist(deviceId, result.radioID, info.name, handle)
                }
            },
            logger = host.connectionLogger,
        )
        if (!persisted) return null
        if (result.radioID != host.radioId) adoptPartition(result.radioID)
        if (!previousSelfKey.contentEquals(info.publicKey)) {
            dropStaleVContact(previousRadioId, previousSelfKey)
        }
        host.selfPublicKey = info.publicKey.copyOf()
        host.roomServerService.setSelfPublicKeyPrefix(host.selfPublicKey)
        return result.radioID
    }

    private suspend fun adoptPartition(newRadioId: Uuid) {
        host.radioId = newRadioId
        host.chatStore.radioID = newRadioId
        host.conversationListBase.radioID = newRadioId
        host.sendQueue.radioID = newRadioId
        host.nodeStore.radioId = newRadioId
        host.contactPathCollector.radioID = newRadioId
        host.nodeStorageCollector.radioID = newRadioId
        host.heardRepeatsService.configure(radioID = newRadioId)
        host.startRxLogCatchUp()
    }

    private suspend fun dropStaleVContact(previousRadioId: Uuid, oldSelfPublicKey: ByteArray) {
        dropStaleVContactAfterIdentityRotation(
            previousRadioId = previousRadioId,
            oldSelfPublicKey = oldSelfPublicKey,
            fetchContactId = { radioId, publicKey -> host.contactStore.fetchContact(radioId, publicKey)?.id },
            deleteContact = { host.contactStore.deleteContact(it) },
            logger = host.connectionLogger,
        )
    }
}
