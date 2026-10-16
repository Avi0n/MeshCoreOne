package one.meshcore.shared.persistence

import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.ContactSyncResult
import one.meshcore.shared.contact.addOrUpdateContact
import one.meshcore.shared.contact.syncContacts
import one.meshcore.shared.model.Uuid

internal enum class RoomContactFrameMergeCase {
    SINGLE,
    INCREMENTAL,
    ;

    suspend fun invoke(service: ContactService, radioID: Uuid, frame: ContactFrame): ContactSyncResult? = when (this) {
        SINGLE -> {
            service.addOrUpdateContact(radioID, frame)
            null
        }
        INCREMENTAL -> service.syncContacts(radioID, since = SINCE)
    }

    companion object {
        private const val SINCE = 1_699_999_000L
    }
}
