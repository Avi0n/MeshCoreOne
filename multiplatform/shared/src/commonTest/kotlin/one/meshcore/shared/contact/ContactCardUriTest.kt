package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Mirrors the contact cases of the iOS `MeshCoreURLParserTests`: type fallback, the injection
 * guardrail, query-significant-character round-trips, the literal `+`, and every contact type. Adds
 * exact-emit assertions on `export` (the byte-exact producer side that pairs with W1-A's decode).
 */
class ContactCardUriTest {
    private companion object {
        /** A real 32-byte public key rendered as uppercase hex (64 chars). */
        const val VALID_HEX = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"

        /** A second, distinct 32-byte key used to prove an injected key never wins. */
        val OTHER_HEX = "BC".repeat(32)

        fun key(): ByteArray = assertNotNull(decodeHex(VALID_HEX))

        fun roundTrip(name: String, type: ContactType = ContactType.CHAT): ContactCard {
            val uri = ContactCardUri.export(name = name, publicKey = key(), type = type)
            return assertNotNull(ContactCardUri.parse(uri), "round-trip should parse for name: $name")
        }
    }

    @Test
    fun parseFallsBackToChatForAnOutOfRangeTypeWithoutTrapping() {
        val url = "meshcore://contact/add?name=Node&public_key=$VALID_HEX&type=300"
        val result = assertNotNull(ContactCardUri.parse(url))
        assertEquals("Node", result.name)
        assertEquals(ContactType.CHAT, result.contactType)
    }

    @Test
    fun parseMapsType2ToRepeater() {
        val url = "meshcore://contact/add?name=Node&public_key=$VALID_HEX&type=2"
        assertEquals(ContactType.REPEATER, assertNotNull(ContactCardUri.parse(url)).contactType)
    }

    // Exact emit (byte-exact producer parity with iOS URLComponents)

    @Test
    fun exportEmitsTheExpectedParamOrderAndUppercaseKey() {
        val uri = ContactCardUri.export(name = "AVN2", publicKey = key(), type = ContactType.CHAT)
        assertEquals("meshcore://contact/add?name=AVN2&public_key=$VALID_HEX&type=1", uri)
    }

    @Test
    fun exportEscapesAmpersandEqualsAndPlusButLeavesQuestionLiteral() {
        // URLComponents escapes & and = (injection guard) but not + or ? — the interop-load-bearing rule.
        assertEquals(
            "meshcore://contact/add?name=a%26b%3Dc&public_key=$VALID_HEX&type=1",
            ContactCardUri.export(name = "a&b=c", publicKey = key(), type = ContactType.CHAT),
        )
        assertEquals(
            "meshcore://contact/add?name=C%2B%2B%20dev?&public_key=$VALID_HEX&type=1",
            ContactCardUri.export(name = "C++ dev?", publicKey = key(), type = ContactType.CHAT),
        )
    }

    // Export/parse round-trip and query injection

    @Test
    fun aNameCarryingAnInjectedQueryDoesNotOverrideTheDeclaredKeyOrType() {
        val spoofName = "Alice&public_key=$OTHER_HEX&type=3"
        val declaredKey = key()

        val uri = ContactCardUri.export(name = spoofName, publicKey = declaredKey, type = ContactType.CHAT)
        val result = assertNotNull(ContactCardUri.parse(uri))

        assertContentEquals(declaredKey, result.publicKey)
        assertEquals(ContactType.CHAT, result.contactType)
        assertEquals(spoofName, result.name)
    }

    @Test
    fun namesContainingQuerySignificantCharactersRoundTripIntact() {
        for (name in listOf("a & b", "a=b", "key & value = pair", "100% sure?", "a#b")) {
            assertEquals(name, roundTrip(name).name)
        }
    }

    @Test
    fun aLiteralPlusInANameIsPreservedNotTurnedIntoASpace() {
        assertEquals("C++ dev", roundTrip("C++ dev").name)
    }

    @Test
    fun aRawPlusFromAStockQrMeansSpaceButEncodedPlusRemainsLiteral() {
        assertEquals(
            "Example Repeater",
            assertNotNull(
                ContactCardUri.parse("meshcore://contact/add?name=Example+Repeater&public_key=$VALID_HEX&type=2"),
            ).name,
        )
        assertEquals(
            "C++",
            assertNotNull(
                ContactCardUri.parse("meshcore://contact/add?name=C%2B%2B&public_key=$VALID_HEX&type=1"),
            ).name,
        )
    }

    @Test
    fun spacesColonsAndUnicodeRoundTripIntact() {
        for (name in listOf("Field Base", "12:30 rally point", "Café au lait", "北京")) {
            assertEquals(name, roundTrip(name).name)
        }
    }

    @Test
    fun everyContactTypeRoundTripsThroughExportAndParse() {
        for (type in ContactType.entries) {
            assertEquals(type, roundTrip("Node", type).contactType)
        }
    }
}
