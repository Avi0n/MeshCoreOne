// PortedFrom: MC1Services/Tests/MC1ServicesTests/ContactShareUtilitiesTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactShareOriginalVectorsTest {
    private val hex = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
    private val key = requireNotNull(Bytes.parseHex(hex))
    private val context = MessageTextNormalizer.StyleContext(isOutgoing = false, currentUserName = null)

    @Test
    @OriginalCase("ContactShareUtilitiesTests::formatShare then parseShare round-trips for every contact type(type : ContactType)")
    fun roundTripAllTypes() {
        for (type in listOf(ContactType.CHAT, ContactType.REPEATER, ContactType.ROOM)) {
            val parsed = requireNotNull(ContactShare.parseShare(ContactShare.formatShare(key, type, "AVN2")))
            assertEquals(key, parsed.publicKey)
            assertEquals(type, parsed.type)
            assertEquals("AVN2", parsed.name)
        }
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::formatShare emits uppercase hex()")
    fun uppercaseHex() {
        assertEquals("<$hex:1:Node>", ComposerShare.myInfoToken(key, "Node"))
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare rejects a 63-char (short) public key()")
    fun rejectShortKey() {
        assertNull(ContactShare.parseShare("<${hex.dropLast(1)}:1:Node>"))
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare rejects non-hex characters in the public key()")
    fun rejectNonHexKey() {
        assertNull(ContactShare.parseShare("<G${hex.substring(1)}:1:Node>"))
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare rejects a token missing the name field()")
    fun rejectMissingNameField() {
        assertNull(ContactShare.parseShare("<$hex:1>"))
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare rejects a token missing type and name()")
    fun rejectMissingTypeAndName() {
        assertNull(ContactShare.parseShare("<$hex>"))
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare rejects an empty name()")
    fun rejectEmptyName() {
        assertNull(ContactShare.parseShare("<$hex:1:>"))
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare rejects out-of-range and overflowing type values without trapping(typeDigits : String)")
    fun rejectInvalidTypes() {
        for (type in listOf("256", "300", "99999999999999999999", "0", "4")) {
            assertNull(ContactShare.parseShare("<$hex:$type:x>"))
        }
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare round-trips a name containing '>' to its stripped form()")
    fun stripClosingAngleBracket() {
        val token = ContactShare.formatShare(key, ContactType.CHAT, "AV>N2")
        assertFalse(token.drop(1).dropLast(1).contains('>'))
        assertEquals("AVN2", requireNotNull(ContactShare.parseShare(token)).name)
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare preserves a colon inside the name()")
    fun preserveColon() {
        assertRoundTripName("Base:Camp:1")
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare preserves a newline inside the name()")
    fun preserveNewline() {
        assertRoundTripName("Line1\nLine2")
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare preserves an RTL-override character in the name()")
    fun preserveRtlOverride() {
        assertRoundTripName("Node\u202Eflip")
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::parseShare round-trips a 1000-character name()")
    fun preserveLongName() {
        assertRoundTripName("n".repeat(1000))
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::extractShares finds multiple tokens amid plain text()")
    fun normalizeMultipleShares() {
        val first = ContactShare.formatShare(key, ContactType.CHAT, "Alice")
        val second = ContactShare.formatShare(key, ContactType.REPEATER, "Bob")
        val result = MessageTextNormalizer.normalize("Add these: $first and also $second thanks", context)
        assertEquals("Add these: Alice and also Bob thanks", result.text)
        assertEquals(listOf(LinkKind.CONTACT_SHARE, LinkKind.CONTACT_SHARE), result.spans.map { it.kind })
        assertEquals(
            listOf(
                "meshcore://contact/add?name=Alice&public_key=$hex&type=1",
                "meshcore://contact/add?name=Bob&public_key=$hex&type=2",
            ),
            result.spans.map { it.url },
        )
    }

    @Test
    @OriginalCase("ContactShareUtilitiesTests::extractShares returns empty when there are no tokens()")
    fun normalizeWithoutShares() {
        val text = "Just some plain text, no tokens here."
        val result = MessageTextNormalizer.normalize(text, context)
        assertEquals(text, result.text)
        assertTrue(result.spans.isEmpty())
    }

    private fun assertRoundTripName(name: String) {
        val parsed = ContactShare.parseShare(ContactShare.formatShare(key, ContactType.CHAT, name))
        assertNotNull(parsed)
        assertEquals(name, requireNotNull(parsed).name)
    }
}
