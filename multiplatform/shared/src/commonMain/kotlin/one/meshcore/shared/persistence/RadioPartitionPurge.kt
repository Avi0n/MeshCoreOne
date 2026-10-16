package one.meshcore.shared.persistence

import one.meshcore.shared.model.Uuid

/**
 * Danger-zone / forget-device helpers that keep Room DAO access inside the shared module (androidApp
 * does not depend on Room and cannot call [MeshDatabase] members that expose RoomDatabase). These are
 * free functions (not extensions) so the androidApp call site never resolves a MeshDatabase receiver.
 */

/** Last-connected epoch seconds for a Device row id, or null when the row is absent. */
suspend fun deviceLastConnected(database: MeshDatabase, deviceId: Uuid): Double? =
    database.deviceDao().getById(deviceId)?.lastConnected

/** Public key for a Device row id, or null when the row is absent. */
suspend fun devicePublicKey(database: MeshDatabase, deviceId: Uuid): ByteArray? =
    database.deviceDao().getById(deviceId)?.publicKey

/** Contact-table cap for a Device row id, or null when the row is absent. */
suspend fun deviceMaxContacts(database: MeshDatabase, deviceId: Uuid): Int? =
    database.deviceDao().getById(deviceId)?.maxContacts

/** Channel-slot cap for a Device row id, or null when the row is absent. */
suspend fun deviceMaxChannels(database: MeshDatabase, deviceId: Uuid): Int? =
    database.deviceDao().getById(deviceId)?.maxChannels

/**
 * Marks every remote-node session disconnected. Call on cold app launch — live login state does not
 * survive process death (iOS `resetAllRemoteNodeSessionConnections`).
 */
suspend fun resetAllRemoteNodeSessionConnections(database: MeshDatabase) {
    val dao = database.remoteNodeSessionDao()
    val rows = dao.getAll()
    if (rows.none { it.isConnected }) return
    dao.upsertAll(rows.map { if (it.isConnected) it.copy(isConnected = false) else it })
}

/**
 * Forget-keep: remint [Device.id], mark inactive, and strip every connection method so the row
 * cannot appear in the connect sheet. Partition children stay under [Device.radioID] for later
 * public-key reconcile (iOS `demoteDeviceToGhost`).
 */
private fun randomGhostDeviceId(): Uuid = Uuid.random()

suspend fun demoteDeviceToGhost(database: MeshDatabase, deviceId: Uuid, newId: () -> Uuid = ::randomGhostDeviceId) {
    val row = database.deviceDao().getById(deviceId) ?: return
    val ghost = row.copy(
        id = newId(),
        isActive = false,
        connectionMethods = emptyList(),
    )
    database.deviceDao().deleteById(deviceId)
    database.deviceDao().upsert(ghost)
}

/** Deletes the device row by id. */
suspend fun deleteDeviceRow(database: MeshDatabase, deviceId: Uuid) {
    database.deviceDao().deleteById(deviceId)
}

/**
 * Deletes every radio-scoped family iOS `deleteDeviceAndData` removes: reactions, room messages,
 * sessions, unused node snapshots, blocked senders, Discover rows, contacts, pending sends, repeats,
 * messages, channels, saved traces, and Device rows.
 */
suspend fun purgeRadioPartition(database: MeshDatabase, radioID: Uuid) {
    database.reactionDao().deleteByRadio(radioID)

    val sessions = database.remoteNodeSessionDao().listByRadio(radioID)
    val sessionKeys = sessions.map { it.publicKey }
    for (session in sessions) {
        database.roomMessageDao().deleteBySession(session.id)
        database.remoteNodeSessionDao().deleteById(session.id)
    }
    val remaining = database.remoteNodeSessionDao().getAll()
    for (publicKey in sessionKeys) {
        if (remaining.none { it.publicKey.contentEquals(publicKey) }) {
            database.nodeStatusSnapshotDao().deleteByNodePublicKey(publicKey)
        }
    }

    database.blockedChannelSenderDao().listByRadio(radioID).forEach {
        database.blockedChannelSenderDao().delete(radioID, it.name)
    }
    database.discoveredNodeDao().deleteByRadio(radioID)
    database.contactDao().listByRadio(radioID).forEach { database.contactDao().deleteById(it.id) }

    database.pendingSendDao().deleteByRadio(radioID)
    val messages = database.messageDao().listByRadio(radioID)
    if (messages.isNotEmpty()) {
        val ids = messages.map { it.id }
        database.messageRepeatDao().deleteByMessageIds(ids)
        database.messageDao().deleteByIds(ids)
    }

    database.channelDao().listByRadio(radioID).forEach { database.channelDao().deleteById(it.id) }
    database.savedTracePathDao().listByRadio(radioID).forEach { path ->
        database.tracePathRunDao().deleteByPath(path.id)
        database.savedTracePathDao().deleteById(path.id)
    }
    database.deviceDao().listByRadio(radioID).forEach { database.deviceDao().deleteById(it.id) }
}
