package one.meshcore.shared.room

import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.chat.ConversationListStore
import one.meshcore.shared.model.Uuid

/**
 * The [ConversationListStore] a live connection hands the conversation list: every read and write
 * delegates to [base], and [leaveRoom] runs the radio sequence leaving a room actually needs — log out of
 * the room server and drop its session, remove the backing contact from the radio, refresh the badge.
 * The Kotlin analogue of the Swift `ChatConversationActions.leaveRoom`.
 *
 * The order is load-bearing: a failed logout throws before the contact is removed, so the room stays
 * reachable for a retry instead of leaving the user authenticated to a room they can no longer address.
 */
class RoomLeavingConversationListStore(
    private val base: ConversationListStore,
    private val roomServerService: RoomServerService,
    private val removeContact: suspend (radioID: Uuid, publicKey: ByteArray) -> Unit,
    private val refreshBadge: suspend () -> Unit,
) : ConversationListStore by base {
    override suspend fun leaveRoom(session: RemoteNodeSessionDto) {
        roomServerService.leaveRoom(session.id, session.publicKey)
        removeContact(session.radioID, session.publicKey)
        refreshBadge()
    }
}
