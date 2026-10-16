package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreUrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContactShareContentTest {
    @Test
    fun compactPublicKeyHexHasNoWhitespace() {
        val hex = ContactShareContent.compactPublicKeyHex(
            byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()),
        )
        assertEquals("AABBCCDD", hex)
        assertFalse(hex.any { it.isWhitespace() })
    }

    @Test
    fun uriIsMeshcoreContactAddAndRoundTrips() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val uri = ContactShareContent.uri(name = "Alice", publicKey = key, type = ContactType.CHAT)
        assertTrue(uri.startsWith("meshcore://contact/add?"))
        assertFalse(uri.contains(" "))
        val parsed = assertNotNull(MeshCoreUrlParser.parseContactUrl(uri))
        assertEquals("Alice", parsed.name)
        assertEquals(key.toUppercaseHex(), parsed.publicKey.toUppercaseHex())
        assertEquals(ContactType.CHAT, parsed.type)
    }

    @Test
    fun uriPreservesContactType() {
        val key = ByteArray(32) { 0xAB.toByte() }
        val uri = ContactShareContent.uri(name = "Relay", publicKey = key, type = ContactType.REPEATER)
        val parsed = assertNotNull(MeshCoreUrlParser.parseContactUrl(uri))
        assertEquals(ContactType.REPEATER, parsed.type)
    }
}
