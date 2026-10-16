package one.meshcore.shared.room.ui

import one.meshcore.shared.runExceptionFallback

/**
 * Persists a room-info pin or notification write. False means the caller must roll the
 * optimistic toggle back. iOS `togglePinned` / `setNotificationLevel` roll back the same way.
 * Cancellation still stops the write, and an Error still leaves it.
 */
suspend fun persistRoomInfoChange(isRadioReady: Boolean = true, persist: suspend () -> Unit): Boolean {
    if (!isRadioReady) return false
    return runExceptionFallback({ false }) {
        persist()
        true
    }
}
