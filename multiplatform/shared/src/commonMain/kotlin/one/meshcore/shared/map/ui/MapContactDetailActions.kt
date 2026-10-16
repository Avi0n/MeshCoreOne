package one.meshcore.shared.map.ui

import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.removeContact
import one.meshcore.shared.contact.removeLocalContact
import one.meshcore.shared.contact.setContactFavorite
import one.meshcore.shared.model.Uuid

internal fun ContactService.favoriteApply(contactId: Uuid, nextFavorite: Boolean): suspend () -> Unit =
    { setContactFavorite(contactId, nextFavorite) }

internal fun ContactService.deleteApply(radioId: Uuid, publicKey: ByteArray, contactId: Uuid): suspend () -> Unit = {
    removeMapContactOrLocal(
        removeRemote = { removeContact(radioId, publicKey) },
        removeLocal = { removeLocalContact(contactId, publicKey) },
    )
}
