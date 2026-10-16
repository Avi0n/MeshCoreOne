package one.meshcore.shared.persistence

import one.meshcore.shared.chat.MessageStore
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeContactStore

internal enum class RoomContactPeerFrameCase {
    NODE,
    CHAT,
    ;

    suspend fun invoke(node: RemoteNodeContactStore, chat: MessageStore, radioID: Uuid, frame: ContactFrame) {
        when (this) {
            NODE -> node.saveContact(radioID, frame)
            CHAT -> chat.saveContact(radioID, frame.toMeshContact())
        }
    }
}
