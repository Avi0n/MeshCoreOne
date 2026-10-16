package one.meshcore.shared.connection

import one.meshcore.shared.settings.PersistentLogger

/** iOS `ConnectionManager` dump when demote-to-ghost persist throws. */
const val FAILED_DEMOTE_DEVICE = "Failed to demote device in SwiftData"

/** iOS `ConnectionManager` dump when delete-device-and-data persist throws. */
const val FAILED_DELETE_DEVICE_DATA = "Failed to delete device data from SwiftData"

/** Disconnect failed while forgetting a radio. The local row removal still runs. */
const val FORGET_DISCONNECT_FAILED = "Forget disconnect failed"

/**
 * Disconnects the live radio before its row is removed. A thrown failure is logged so the local
 * delete or demote still runs. Cancellation still stops the forget.
 */
suspend fun disconnectBeforeForget(warning: (String) -> Unit, disconnect: suspend () -> Unit) {
    runConnectionPersist(
        onFailure = { failure -> warning("$FORGET_DISCONNECT_FAILED: ${failure.message ?: failure}") },
        fallback = {},
        block = disconnect,
    )
}

/**
 * Applies forget-device persist. A Room fault is logged with the iOS string so forget can still
 * clear reconnect intent. Cancellation still stops the forget.
 */
suspend fun applyForgetDevicePersist(deleteData: Boolean, persist: suspend () -> Unit, logger: PersistentLogger) {
    runConnectionPersist(
        onFailure = { error ->
            val prefix = if (deleteData) FAILED_DELETE_DEVICE_DATA else FAILED_DEMOTE_DEVICE
            logger.warning("$prefix: ${error.message ?: error}")
        },
        fallback = {},
        block = persist,
    )
}
