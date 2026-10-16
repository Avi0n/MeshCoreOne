package one.meshcore.shared.notification

import one.meshcore.shared.chat.ActiveChannel
import one.meshcore.shared.model.Uuid

/** Clears a channel conversation's delivered tray rows after a delete/clear (iOS clearNotificationsForChannel). */
suspend fun NotificationCoordinator.removeDeliveredForChannel(radioID: Uuid, channelIndex: Int) {
    runtime.poster.removeDeliveredForChannel(radioID, channelIndex)
}

/** Clears a room's delivered tray rows (iOS removeDeliveredNotifications(forRoomSessionID:)). */
suspend fun NotificationCoordinator.removeDeliveredForRoom(sessionID: Uuid) {
    runtime.poster.removeDeliveredForRoom(sessionID)
}

/**
 * Drops tray rows for the conversation still on screen after a return to the foreground.
 * Messages posted while the app was backgrounded (the iOS `willPresent` gate is off-screen
 * only) stay in the shade until this re-clear, matching `ChatConversationView` / `RoomConversationView`
 * `scenePhase == .active`.
 */
suspend fun NotificationCoordinator.clearDeliveredForActiveConversation() {
    runtime.activeConversation.activeContactID()?.let { runtime.poster.removeDeliveredForContact(it) }
    runtime.activeConversation.activeChannel()?.let { runtime.poster.removeDeliveredForChannel(it.radioID, it.index) }
    runtime.activeConversation.activeRoomSessionID()?.let { runtime.poster.removeDeliveredForRoom(it) }
    updateBadge()
}

// Opening a conversation both claims the active slot and clears what the tray already delivered for
// it, then recomputes the badge over the unread rows the open just cleared — the two halves iOS runs
// together in `ChatConversationView.clearDeliveredNotifications`.
suspend fun NotificationCoordinator.setActiveContact(contactID: Uuid?) {
    runtime.activeConversation.setActiveContact(contactID)
    if (contactID == null) return
    runtime.poster.removeDeliveredForContact(contactID)
    updateBadge()
}

suspend fun NotificationCoordinator.setActiveChannel(radioID: Uuid, channelIndex: Int) {
    runtime.activeConversation.setActiveChannel(ActiveChannel(radioID, channelIndex))
    runtime.poster.removeDeliveredForChannel(radioID, channelIndex)
    updateBadge()
}

suspend fun NotificationCoordinator.setActiveRoom(sessionID: Uuid) {
    runtime.activeConversation.setActiveRoom(sessionID)
    runtime.poster.removeDeliveredForRoom(sessionID)
    updateBadge()
}

suspend fun NotificationCoordinator.clearActiveConversation() = runtime.activeConversation.clear()

/**
 * Releases the slot [contactID]'s thread holds, and only that one: a teardown can land after a newer
 * conversation has already claimed the slot, and clearing it then would un-suppress the conversation
 * the user is reading. Swift `ChatConversationView.performCleanup` applies the same guard.
 */
suspend fun NotificationCoordinator.clearActiveContact(contactID: Uuid) {
    if (runtime.activeConversation.activeContactID() != contactID) return
    runtime.activeConversation.clear()
}

/** The channel counterpart of [clearActiveContact], keyed on the radio and the firmware slot. */
suspend fun NotificationCoordinator.clearActiveChannel(radioID: Uuid, channelIndex: Int) {
    if (runtime.activeConversation.activeChannel() != ActiveChannel(radioID, channelIndex)) return
    runtime.activeConversation.clear()
}
