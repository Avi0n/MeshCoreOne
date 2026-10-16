package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto

/** Distinguishes an absent contact from a local-store lookup that failed after retry. */
internal sealed class ContactLookup {
    data class Ok(val contact: ContactDto?) : ContactLookup()
    data object Failed : ContactLookup()
}
