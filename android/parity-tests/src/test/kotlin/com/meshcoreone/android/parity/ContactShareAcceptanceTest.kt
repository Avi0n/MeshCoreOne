// PortedFrom: MC1Tests/Views/Contacts/ContactShareContentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-501 Preserve literal source URI fields and whitespace assertions through the actual service/parser.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.deeplinks.MeshCoreDeepLink
import com.meshcoreone.android.app.deeplinks.MeshCoreUriParser
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.contacts.ContactService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Test

class ContactShareAcceptanceTest {
    @Test fun originalContactShareFieldsRoundTripWithoutWhitespace() {
        val key = Bytes(ByteArray(32) { 0xAB.toByte() })
        for ((name, type) in listOf("Alice" to ContactType.CHAT, "Relay" to ContactType.REPEATER)) {
            val uri = ContactService.exportContactURI(name, key, type)
            assertTrue(uri.startsWith("meshcore://contact/add?"))
            assertFalse(uri.any { it.isWhitespace() })
            val parsed = assertIs<MeshCoreDeepLink.Contact>(MeshCoreUriParser.parseContact(uri))
            assertEquals(name, parsed.name)
            assertEquals(key, parsed.publicKey)
            assertEquals(type, parsed.contactType)
        }
    }
}
