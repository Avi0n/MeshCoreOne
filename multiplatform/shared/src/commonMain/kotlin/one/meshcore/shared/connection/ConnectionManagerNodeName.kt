package one.meshcore.shared.connection

import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.contact.utf8BytePrefix

/**
 * Renames the connected radio, clamped to the usable name budget, and proves the write stuck before
 * persisting it — a radio that rejects the write answers with its old name rather than an error.
 * Mirrors iOS `SettingsService.setNodeNameVerified`. Returns the name the radio adopted.
 */
suspend fun ConnectionManager.setNodeName(name: String): String {
    val liveSession = session ?: error("No radio connected")
    val clamped = name.utf8BytePrefix(MAX_USABLE_NAME_BYTES)
    liveSession.setName(clamped)
    val info = liveSession.getSelfInfo()
    if (info.name != clamped) {
        throw NodeNameVerificationFailed(expected = clamped, actual = info.name)
    }
    val existing = deviceRow() ?: return clamped
    persistDeviceSettingsAfterRadioAck(logger, FAILED_PERSIST_NODE_NAME) {
        database.deviceDao().upsert(existing.copy(nodeName = clamped))
    }
    // The BLE-menu header reads the last-connection record, so a rename that skipped it would be
    // undone by the next connection-state emission.
    lastConnectionStore?.let { store ->
        val radio = store.radioId()
        val handle = store.handle()
        if (radio != null && handle != null) {
            applyLastConnectionPersist(
                persist = { store.persist(existing.id, radio, clamped, handle) },
                logger = logger,
                failLine = FAILED_PERSIST_LAST_CONNECTION,
            )
        }
    }
    return clamped
}
