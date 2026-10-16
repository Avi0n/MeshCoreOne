package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Mirrors the iOS `ContactShareUtilitiesTests`: round-trip per type, uppercase-hex emit, malformed
 * rejection, the adversarial type-bounds regression, name escaping, and `extractAll`. This is the
 * chat-token half of the cross-device interop guard.
 */
class ContactShareTokenTest {
    private companion object {
        /** A real 32-byte public key rendered as uppercase hex (64 chars). */
        const val VALID_HEX = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"

        fun key(): ByteArray = assertNotNull(decodeHex(VALID_HEX))
    }

    // Round-trip

    @Test
    fun formatThenParseRoundTripsForEveryContactType() {
        for (type in ContactType.entries) {
            val name = "AVN2"
            val token = ContactShareToken.format(publicKey = key(), type = type, name = name)
            val result = assertNotNull(ContactShareToken.parse(token))

            assertEquals(VALID_HEX, result.id)
            assertEquals(type, result.contactType)
            assertEquals(name, result.name)
        }
    }

    @Test
    fun formatEmitsUppercaseHex() {
        val token = ContactShareToken.format(publicKey = key(), type = ContactType.CHAT, name = "Node")
        assertEquals("<$VALID_HEX:1:Node>", token)
    }

    // Malformed rejection

    @Test
    fun parseRejectsAShortPublicKey() {
        val shortHex = VALID_HEX.dropLast(1)
        assertNull(ContactShareToken.parse("<$shortHex:1:Node>"))
    }

    @Test
    fun parseRejectsNonHexCharactersInThePublicKey() {
        val nonHex = "G1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
        assertNull(ContactShareToken.parse("<$nonHex:1:Node>"))
    }

    @Test
    fun parseRejectsATokenMissingTheNameField() {
        assertNull(ContactShareToken.parse("<$VALID_HEX:1>"))
    }

    @Test
    fun parseRejectsATokenMissingTypeAndName() {
        assertNull(ContactShareToken.parse("<$VALID_HEX>"))
    }

    @Test
    fun parseRejectsAnEmptyName() {
        assertNull(ContactShareToken.parse("<$VALID_HEX:1:>"))
    }

    // Adversarial type bounds (regression for the trap)

    @Test
    fun parseRejectsOutOfRangeAndOverflowingTypeValuesWithoutTrapping() {
        for (typeDigits in listOf("256", "300", "99999999999999999999", "0", "4")) {
            assertNull(ContactShareToken.parse("<$VALID_HEX:$typeDigits:x>"), "type=$typeDigits must reject")
        }
    }

    // Name escaping

    @Test
    fun parseRoundTripsANameContainingGreaterThanToItsStrippedForm() {
        val token = ContactShareToken.format(publicKey = key(), type = ContactType.CHAT, name = "AV>N2")
        assertFalse(token.substring(1, token.length - 1).contains('>'))

        val result = assertNotNull(ContactShareToken.parse(token))
        assertEquals("AVN2", result.name)
    }

    @Test
    fun parsePreservesAColonInsideTheName() {
        val name = "Base:Camp:1"
        val token = ContactShareToken.format(publicKey = key(), type = ContactType.CHAT, name = name)
        assertEquals(name, assertNotNull(ContactShareToken.parse(token)).name)
    }

    @Test
    fun parsePreservesANewlineInsideTheName() {
        val name = "Line1\nLine2"
        val token = ContactShareToken.format(publicKey = key(), type = ContactType.CHAT, name = name)
        assertEquals(name, assertNotNull(ContactShareToken.parse(token)).name)
    }

    @Test
    fun parsePreservesAnRtlOverrideCharacterInTheName() {
        val name = "Node\u202Eflip"
        val token = ContactShareToken.format(publicKey = key(), type = ContactType.CHAT, name = name)
        assertEquals(name, assertNotNull(ContactShareToken.parse(token)).name)
    }

    @Test
    fun parseRoundTripsA1000CharacterName() {
        val name = "n".repeat(1000)
        val token = ContactShareToken.format(publicKey = key(), type = ContactType.CHAT, name = name)
        assertEquals(name, assertNotNull(ContactShareToken.parse(token)).name)
    }

    // extractAll

    @Test
    fun extractAllFindsMultipleTokensAmidPlainText() {
        val first = ContactShareToken.format(publicKey = key(), type = ContactType.CHAT, name = "Alice")
        val second = ContactShareToken.format(publicKey = key(), type = ContactType.REPEATER, name = "Bob")
        val text = "Add these: $first and also $second thanks"

        val results = ContactShareToken.extractAll(text)
        assertEquals(2, results.size)
        assertEquals("Alice", results[0].name)
        assertEquals(ContactType.CHAT, results[0].contactType)
        assertEquals("Bob", results[1].name)
        assertEquals(ContactType.REPEATER, results[1].contactType)
    }

    @Test
    fun extractAllReturnsEmptyWhenThereAreNoTokens() {
        assertEquals(emptyList(), ContactShareToken.extractAll("Just some plain text, no tokens here."))
    }
}
