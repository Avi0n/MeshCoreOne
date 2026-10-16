package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid

/**
 * Reports a brand-new contact discovered over a 0x80 advertisement (never a known-contact refresh), so the
 * platform can notify. Callers are [ContactService.handleAdvertisement]'s unknown-contact save and the
 * advert-delta reconcile for keys this round actually inserted, mirroring iOS
 * `NotificationService.postNewContactNotification`.
 */
fun interface ContactDiscoveryNotifier {
    suspend fun notifyNewContact(contactID: Uuid, contactName: String?, contactType: ContactType)

    companion object {
        val NoOp = ContactDiscoveryNotifier { _, _, _ -> }
    }
}
