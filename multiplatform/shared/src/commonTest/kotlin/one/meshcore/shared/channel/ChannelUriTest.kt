package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.decodeHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Producer-side coverage for the channel QR/URI, pairing with the W1-A decode tests in
 * `MeshCoreUrlParserTest`. Mirrors the contact-URI test shape: exact emit, `&`/`=` escaping (the
 * injection guard), literal `+`, query-significant-character and unicode round-trips.
 */
class ChannelUriTest {
    private companion object {
        const val SECRET_HEX = "DD07B663EC5851EBE92C71FB0AD9BEF0"
        const val ZERO_HEX = "00000000000000000000000000000000"

        fun secret(): ByteArray = assertNotNull(decodeHex(SECRET_HEX))

        fun roundTrip(name: String): MeshCoreUriChannel {
            val uri = ChannelUri.export(name = name, secret = secret())
            val parsed = assertNotNull(ChannelUri.parse(uri), "round-trip should parse for name: $name")
            return MeshCoreUriChannel(parsed.name, parsed.id)
        }
    }

    private data class MeshCoreUriChannel(val name: String, val secretHex: String)

    @Test
    fun exportEmitsExpectedParamOrderAndUppercaseSecret() {
        assertEquals(
            "meshcore://channel/add?name=Ops&secret=$SECRET_HEX",
            ChannelUri.export(name = "Ops", secret = secret()),
        )
    }

    @Test
    fun exportEscapesAmpersandEqualsAndPlusButLeavesQuestionLiteral() {
        assertEquals(
            "meshcore://channel/add?name=a%26b%3Dc&secret=$SECRET_HEX",
            ChannelUri.export(name = "a&b=c", secret = secret()),
        )
        assertEquals(
            "meshcore://channel/add?name=C%2B%2B%20dev?&secret=$SECRET_HEX",
            ChannelUri.export(name = "C++ dev?", secret = secret()),
        )
    }

    @Test
    fun exportUppercasesSecretHex() {
        val lowerSourced = assertNotNull(decodeHex("aabbccddeeff00112233445566778899"))
        assertEquals(
            "meshcore://channel/add?name=x&secret=AABBCCDDEEFF00112233445566778899",
            ChannelUri.export(name = "x", secret = lowerSourced),
        )
    }

    @Test
    fun aNameCarryingAnInjectedSecretDoesNotOverrideTheDeclaredSecret() {
        // A crafted name embedding its own &secret= must not survive as a query param and downgrade
        // the channel to the attacker's key.
        val spoofName = "Ops&secret=$ZERO_HEX"
        val declared = secret()

        val uri = ChannelUri.export(name = spoofName, secret = declared)
        val parsed = assertNotNull(ChannelUri.parse(uri))

        assertContentEquals(declared, parsed.secret)
        assertEquals(spoofName, parsed.name)
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
    fun rawPlusDecodesAsSpaceInNameAndRegion() {
        val parsed = assertNotNull(
            ChannelUri.parse("meshcore://channel/add?name=Ops+Base&secret=$SECRET_HEX&region_scope=West+Coast"),
        )
        assertEquals("Ops Base", parsed.name)
        assertEquals("West Coast", parsed.regionScope)
    }

    @Test
    fun spacesColonsAndUnicodeRoundTripIntact() {
        for (name in listOf("Field Base", "12:30 rally point", "Café au lait", "北京")) {
            assertEquals(name, roundTrip(name).name)
        }
    }

    @Test
    fun everyRoundTripPreservesTheSecret() {
        assertEquals(SECRET_HEX, roundTrip("Ops").secretHex)
    }

    @Test
    fun parseRejectsAWrongLengthSecret() {
        assertNull(ChannelUri.parse("meshcore://channel/add?name=Ops&secret=AABBCC"))
    }

    @Test
    fun exportAppendsRegionScopeForARegionFloodScope() {
        assertEquals(
            "meshcore://channel/add?name=Ops&secret=$SECRET_HEX&region_scope=West",
            ChannelUri.export(name = "Ops", secret = secret(), floodScope = ChannelFloodScope.Region("West")),
        )
    }

    @Test
    fun exportOmitsRegionScopeForInheritAllRegionsAndEmptyRegion() {
        val base = "meshcore://channel/add?name=Ops&secret=$SECRET_HEX"
        assertEquals(base, ChannelUri.export(name = "Ops", secret = secret(), floodScope = ChannelFloodScope.Inherit))
        assertEquals(
            base,
            ChannelUri.export(name = "Ops", secret = secret(), floodScope = ChannelFloodScope.AllRegions),
        )
        assertEquals(
            base,
            ChannelUri.export(name = "Ops", secret = secret(), floodScope = ChannelFloodScope.Region("")),
        )
    }

    @Test
    fun exportPercentEncodesTheRegionScopeAndRoundTrips() {
        val uri = ChannelUri.export(
            name = "Ops",
            secret = secret(),
            floodScope = ChannelFloodScope.Region("a&b=c"),
        )
        assertEquals("meshcore://channel/add?name=Ops&secret=$SECRET_HEX&region_scope=a%26b%3Dc", uri)
        assertEquals("a&b=c", assertNotNull(ChannelUri.parse(uri)).regionScope)
    }
}
