package one.meshcore.shared.navigation

import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.MAX_REGION_NAME_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `MeshCoreURLParserTests` (12 tests): contact type fallback, injection guardrail,
 * name round-trips (query-significant chars, literal `+`, spaces/colons/unicode, every contact type),
 * and the map parse/reject cases.
 */
class MeshCoreUrlParserTest {
    private companion object {
        /** A real 32-byte public key rendered as uppercase hex (64 chars). */
        const val VALID_HEX = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"

        /** A second, distinct 32-byte key used to prove an injected key never wins. */
        val OTHER_HEX = "BC".repeat(32)

        /** A valid 16-byte channel secret as hex. */
        const val SECRET_HEX = "000102030405060708090A0B0C0D0E0F"

        /** Cap on the app-side `region_scope` name, in UTF-8 bytes; the shared region-name cap. */
        const val REGION_SCOPE_MAX_BYTES = MAX_REGION_NAME_BYTES

        /** Golden hashtag PSK vectors: SHA-256 of the full hashtag name, first 16 bytes, lowercase hex. */
        val GOLDEN_HASHTAG_SECRETS = listOf(
            "#test" to "9cd8fcf22a47333b591d96a2b848b73f",
            "#avion-testing2" to "3976fbac9120f147576900ac90d41dd2",
        )
    }

    // MARK: Contact type fallback

    @Test
    fun parseContactUrlFallsBackToChatForOutOfRangeTypeWithoutTrapping() {
        val url = "meshcore://contact/add?name=Node&public_key=$VALID_HEX&type=300"
        val result = assertNotNull(MeshCoreUrlParser.parseContactUrl(url))
        assertEquals("Node", result.name)
        assertEquals(ContactType.CHAT, result.type)
    }

    @Test
    fun parseContactUrlMapsType2ToRepeater() {
        val url = "meshcore://contact/add?name=Node&public_key=$VALID_HEX&type=2"
        val result = assertNotNull(MeshCoreUrlParser.parseContactUrl(url))
        assertEquals(ContactType.REPEATER, result.type)
    }

    // MARK: Export/parse round-trip and query injection

    @Test
    fun nameCarryingInjectedPublicKeyOrTypeDoesNotOverrideDeclaredKeyOrType() {
        val spoofName = "Alice&public_key=$OTHER_HEX&type=3"
        val uri = buildContactUri(spoofName, VALID_HEX, ContactType.CHAT)

        val result = assertNotNull(MeshCoreUrlParser.parseContactUrl(uri))
        assertEquals(VALID_HEX, result.publicKey.toUppercaseHex(), "Declared key must win over the injected public_key")
        assertEquals(ContactType.CHAT, result.type, "Declared type must win over the injected type")
        assertEquals(spoofName, result.name, "The whole injected string is the name, preserved verbatim")
    }

    @Test
    fun namesContainingQuerySignificantCharactersRoundTripIntact() {
        for (name in listOf("a & b", "a=b", "key & value = pair", "100% sure?", "a#b")) {
            assertEquals(name, roundTripContact(name).name, "name should survive round-trip: $name")
        }
    }

    @Test
    fun aLiteralPlusInANameIsPreservedNotTurnedIntoASpace() {
        assertEquals("C++ dev", roundTripContact("C++ dev").name)
    }

    @Test
    fun spacesColonsAndUnicodeRoundTripIntact() {
        for (name in listOf("Field Base", "12:30 rally point", "Café au lait", "北京")) {
            assertEquals(name, roundTripContact(name).name, "name should survive round-trip: $name")
        }
    }

    @Test
    fun everyContactTypeRoundTripsThroughExportAndParse() {
        for (type in listOf(ContactType.CHAT, ContactType.REPEATER, ContactType.ROOM)) {
            assertEquals(type, roundTripContact("Node", type).type)
        }
    }

    // MARK: Channel

    @Test
    fun parsesAValidChannelLink() {
        val result = assertNotNull(
            MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Base&secret=$SECRET_HEX"),
        )
        assertEquals("Base", result.name)
        assertEquals(SECRET_HEX, result.id)
    }

    @Test
    fun rejectsAChannelSecretThatIsNotSixteenBytes() {
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Base&secret=0011"))
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Base&secret=$VALID_HEX"))
    }

    @Test
    fun rejectsAChannelWithAnEmptyName() {
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=&secret=$SECRET_HEX"))
    }

    // MARK: Secretless hashtag channel joins

    @Test
    fun parsesAnExplicitSecretPrivateChannel() {
        val result = assertNotNull(
            MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Ops&secret=$SECRET_HEX"),
        )
        assertEquals("Ops", result.name)
        assertEquals(SECRET_HEX, result.id)
        assertNull(result.regionScope)
    }

    @Test
    fun derivesSecretForSecretlessHashtagNames() {
        for ((name, hex) in GOLDEN_HASHTAG_SECRETS) {
            val url = "meshcore://channel/add?name=${percentEncode(name)}"
            val result = assertNotNull(MeshCoreUrlParser.parseChannelUrl(url), "should parse $name")
            assertEquals(name, result.name)
            assertEquals(hex.uppercase(), result.id)
            assertContentEquals(ChannelSecret.hashSecret(name), result.secret)
        }
    }

    @Test
    fun normalizesHashtagCaseWhenDerivingSecret() {
        val result = assertNotNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23Test"))
        assertEquals("#test", result.name)
        assertContentEquals(ChannelSecret.hashSecret("#test"), result.secret)
        assertEquals("9cd8fcf22a47333b591d96a2b848b73f".uppercase(), result.id)
    }

    @Test
    fun rejectsSecretlessBareNames() {
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Ops"))
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=test"))
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Ops&secret="))
    }

    @Test
    fun rejectsSecretlessInvalidHashtagBodies() {
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23"))
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23-bad"))
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23has_underscore"))
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23has%20space"))
    }

    @Test
    fun rejectsPresentButInvalidSecretsWithoutFallingBackToNameHash() {
        val hashtag = "%23test"
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=$hashtag&secret=ZZ"))
        assertNull(MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=$hashtag&secret=AABB"))
        assertNull(
            MeshCoreUrlParser.parseChannelUrl(
                "meshcore://channel/add?name=$hashtag&secret=00112233445566778899AABBCCDDEE",
            ),
        )
    }

    @Test
    fun keepsExplicitSecretWhenNameIsHashtagShaped() {
        val result = assertNotNull(
            MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23test&secret=$SECRET_HEX"),
        )
        assertEquals("#test", result.name)
        assertEquals(SECRET_HEX, result.id)
        assertFalse(result.secret.contentEquals(ChannelSecret.hashSecret("#test")))
    }

    @Test
    fun treatsEmptySecretAsMissingForHashtagDerivation() {
        val result = assertNotNull(
            MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23test&secret="),
        )
        assertEquals("#test", result.name)
        assertContentEquals(ChannelSecret.hashSecret("#test"), result.secret)
    }

    @Test
    fun readsOptionalRegionScope() {
        val result = assertNotNull(
            MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=%23avion-testing2&region_scope=testregion"),
        )
        assertEquals("testregion", result.regionScope)
        assertEquals("3976fbac9120f147576900ac90d41dd2".uppercase(), result.id)
    }

    @Test
    fun ignoresEmptyAndWhitespaceRegionScope() {
        val empty = assertNotNull(
            MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Ops&secret=$SECRET_HEX&region_scope="),
        )
        assertNull(empty.regionScope)
        val blank = assertNotNull(
            MeshCoreUrlParser.parseChannelUrl("meshcore://channel/add?name=Ops&secret=$SECRET_HEX&region_scope=%20%20"),
        )
        assertNull(blank.regionScope)
    }

    @Test
    fun trimsAndCapsRegionScopeLength() {
        val longName = "a".repeat(REGION_SCOPE_MAX_BYTES + 10)
        val url = "meshcore://channel/add?name=Ops&secret=$SECRET_HEX&region_scope=%20$longName%20"
        val result = assertNotNull(MeshCoreUrlParser.parseChannelUrl(url))
        assertEquals("a".repeat(REGION_SCOPE_MAX_BYTES), result.regionScope)
    }

    @Test
    fun hashtagSecretMismatchIsTrueOnlyWhenSecretIsNotThePublicHash() {
        val privateSecret = assertNotNull(decodeHex(SECRET_HEX))
        assertTrue(MeshCoreDeepLink.Channel(name = "#test", secret = privateSecret).hasHashtagSecretMismatch)
        assertFalse(
            MeshCoreDeepLink.Channel(name = "#test", secret = ChannelSecret.hashSecret("#test"))
                .hasHashtagSecretMismatch,
        )
        assertFalse(MeshCoreDeepLink.Channel(name = "Ops", secret = privateSecret).hasHashtagSecretMismatch)
    }

    @Test
    fun anInjectedRegionScopeInTheNameDoesNotOverrideTheDeclaredRegionScope() {
        val spoofName = "Ops&region_scope=injected"
        val url = "meshcore://channel/add?name=${percentEncode(spoofName)}&secret=$SECRET_HEX&region_scope=real"
        val result = assertNotNull(MeshCoreUrlParser.parseChannelUrl(url))
        assertEquals(spoofName, result.name)
        assertEquals("real", result.regionScope)
    }

    @Test
    fun rejectsAContactPublicKeyThatIsNotThirtyTwoBytes() {
        assertNull(MeshCoreUrlParser.parseContactUrl("meshcore://contact/add?name=Node&public_key=$SECRET_HEX"))
    }

    // MARK: parseSendMessageUrl

    @Test
    fun parseSendMessageUrlReadsKindIdAndText() {
        val send = assertNotNull(
            MeshCoreUrlParser.parseSendMessageUrl(
                "meshcore://send?kind=contact&id=A0000000-0000-0000-0000-000000000001&text=hello",
            ),
        )
        assertEquals(MeshCoreDeepLink.SendMessage.Kind.CONTACT, send.kind)
        assertEquals("A0000000-0000-0000-0000-000000000001", send.targetId)
        assertEquals("hello", send.text)
    }

    @Test
    fun parseSendMessageUrlRejectsAMissingOrEmptyField() {
        assertNull(
            MeshCoreUrlParser.parseSendMessageUrl(
                "meshcore://send?kind=contact&id=A0000000-0000-0000-0000-000000000001",
            ),
        )
        assertNull(MeshCoreUrlParser.parseSendMessageUrl("meshcore://send?kind=channel&text=hi"))
        assertNull(
            MeshCoreUrlParser.parseSendMessageUrl(
                "meshcore://send?kind=room&id=A0000000-0000-0000-0000-000000000001&text=hi",
            ),
        )
        assertNull(
            MeshCoreUrlParser.parseSendMessageUrl(
                "meshcore://send?kind=contact&id=A0000000-0000-0000-0000-000000000001&text=",
            ),
        )
    }

    @Test
    fun parseSendMessageUrlAcceptsADisplayName() {
        val send = assertNotNull(
            MeshCoreUrlParser.parseSendMessageUrl("meshcore://send?kind=contact&name=Alice&text=hello"),
        )
        assertEquals("Alice", send.targetId)
        assertEquals("Alice", send.targetLabel)
    }

    @Test
    fun parseSendMessageUrlAcceptsACompositeId() {
        val composite = "11111111-1111-1111-1111-111111111111/contact/abcd"
        val send = assertNotNull(
            MeshCoreUrlParser.parseSendMessageUrl("meshcore://send?kind=contact&id=$composite&text=hello"),
        )
        assertEquals(composite, send.targetId)
    }

    // MARK: parseSendAdvertUrl

    @Test
    fun parseSendAdvertUrlDefaultsToZeroHop() {
        assertEquals(false, assertNotNull(MeshCoreUrlParser.parseSendAdvertUrl("meshcore://advert")).flood)
        assertEquals(
            false,
            assertNotNull(MeshCoreUrlParser.parseSendAdvertUrl("meshcore://advert?reach=zeroHop")).flood,
        )
    }

    @Test
    fun parseSendAdvertUrlReadsFloodReach() {
        assertEquals(true, assertNotNull(MeshCoreUrlParser.parseSendAdvertUrl("meshcore://advert?reach=flood")).flood)
    }

    @Test
    fun parseSendAdvertUrlRejectsAnUnknownReach() {
        assertNull(MeshCoreUrlParser.parseSendAdvertUrl("meshcore://advert?reach=mesh"))
        assertNull(MeshCoreUrlParser.parseSendAdvertUrl("meshcore://advert/extra"))
    }

    // MARK: parseMapUrl

    @Test
    fun parseMapUrlParsesAValidMapLinkIntoTheCorrectCoordinate() {
        val coordinate = assertNotNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=37.334900&lon=-122.009020"))
        assertEquals(37.3349, coordinate.latitude, 0.000001)
        assertEquals(-122.00902, coordinate.longitude, 0.000001)
    }

    @Test
    fun parseMapUrlReturnsNullWhenLatOrLonIsMissing() {
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=37.0"))
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lon=-122.0"))
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map"))
    }

    @Test
    fun parseMapUrlRejectsOutOfRangeCoordinates() {
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=91.0&lon=0.0"))
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=0.0&lon=181.0"))
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=-90.001&lon=0.0"))
    }

    @Test
    fun parseMapUrlRejectsANonMapHost() {
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://contact?lat=10.0&lon=10.0"))
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://channel/add?lat=10.0&lon=10.0"))
    }

    @Test
    fun parseMapUrlRejectsNonFiniteAndHexFloatValuesThatDoubleWouldOtherwiseAccept() {
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=nan&lon=0.0"))
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=0.0&lon=inf"))
        assertNull(MeshCoreUrlParser.parseMapUrl("meshcore://map?lat=0x1p4&lon=0.0"))
    }

    // MARK: Helpers

    private fun roundTripContact(name: String, type: ContactType = ContactType.CHAT): MeshCoreDeepLink.Contact =
        assertNotNull(MeshCoreUrlParser.parseContactUrl(buildContactUri(name, VALID_HEX, type)))

    /**
     * Builds a contact URI the way a producer would: reserved characters in the name are percent-encoded
     * per RFC 3986, including literal `+` as `%2B`, so the parser's decode is exercised independently.
     */
    private fun buildContactUri(name: String, publicKeyHex: String, type: ContactType): String =
        "meshcore://contact/add?name=${percentEncode(name)}&public_key=$publicKeyHex&type=${type.raw}"

    private fun percentEncode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            when {
                char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~" -> append(char)
                else -> append('%').append(byteArrayOf(byte).toUppercaseHex())
            }
        }
    }
}
