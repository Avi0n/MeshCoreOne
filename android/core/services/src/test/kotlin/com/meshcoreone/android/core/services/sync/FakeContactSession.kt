// AndroidOnly: WP-214 Contact-session input double for the real ContactService in original pruning cases.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ContactFetchResult
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import java.time.Instant
import java.util.Collections

/** Swift `MockMeshCoreSession`'s contact stream: stubbed contacts, reported total, recorded `since`. */
internal class FakeContactSession : ContactSessionOps {
    @Volatile var stubbedContacts: List<MeshContact> = emptyList()
    @Volatile var stubbedReportedTotal: Long? = null
    @Volatile var stubbedReportsNoTotal = false
    val getContactsInvocations: MutableList<Instant?> = Collections.synchronizedList(ArrayList())

    /** Swift `getContactsReportingTotal(since:)`: returns every stub regardless of `since`. */
    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult {
        getContactsInvocations += since
        val total = if (stubbedReportsNoTotal) null else stubbedReportedTotal ?: stubbedContacts.size.toLong()
        return ContactFetchResult(stubbedContacts, total)
    }

    override suspend fun getContacts(since: Instant?): List<MeshContact> = getContactsReportingTotal(since).contacts
    override suspend fun getContact(publicKey: Bytes): Nothing = unexpected()
    override suspend fun addContact(contact: MeshContact): Nothing = unexpected()
    override suspend fun removeContact(publicKey: Bytes): Nothing = unexpected()
    override suspend fun resetPath(publicKey: Bytes): Nothing = unexpected()
    override suspend fun sendPathDiscovery(destination: Bytes): Nothing = unexpected()
    override suspend fun shareContact(publicKey: Bytes): Nothing = unexpected()
    override suspend fun exportContact(publicKey: Bytes?): Nothing = unexpected()
    override suspend fun importContact(cardData: Bytes): Nothing = unexpected()
    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags): Nothing = unexpected()
    private fun unexpected(): Nothing = throw AssertionError("Unexpected non-fetch contact-session operation")
}
