package one.meshcore.shared.meshcore

/**
 * Contacts from a GET_CONTACTS reply plus the device's reported total.
 *
 * [reportedTotal] is the `contactsStart` header count. On a full fetch (`since == null`) it is the
 * device's complete contact count, so a prune caller can skip when the received set is truncated.
 * Null means the header never arrived, so the snapshot cannot be proven complete.
 */
data class ContactFetchResult(val contacts: List<MeshContact>, val reportedTotal: Int?)
