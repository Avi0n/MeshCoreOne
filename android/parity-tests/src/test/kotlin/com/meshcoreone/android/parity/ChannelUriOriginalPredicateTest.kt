// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.deeplinks.ChannelJoinFloodScopeApplier
import com.meshcoreone.android.app.deeplinks.MeshCoreDeepLink
import com.meshcoreone.android.app.deeplinks.MeshCoreUriParser
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.contacts.ChannelService
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelUriOriginalPredicateTest {
    private val privateSecretHex = "00112233445566778899AABBCCDDEEFF"
    private val privateSecret = assertNotNull(Bytes.parseHex(privateSecretHex))

    @Test
    fun originalPrivateAndHashtagExplicitSecretsRetainAllDeclaredFields() {
        val private = channel("meshcore://channel/add?name=Ops&secret=$privateSecretHex")
        assertEquals("Ops", private.name)
        assertEquals(privateSecret, private.secret)
        assertNull(private.regionScope)

        val hashtag = channel("meshcore://channel/add?name=%23test&secret=$privateSecretHex")
        assertEquals("#test", hashtag.name)
        assertEquals(privateSecret, hashtag.secret)
        assertNotEquals(ChannelService.hashSecret("#test"), hashtag.secret)
    }

    @Test
    fun originalPublicChannelRetainsItsFixedKeyAndName() {
        val publicSecretHex = "8b3387e9c5cdea6ac9e5edbaa115cd72"
        val parsed = channel("meshcore://channel/add?name=Public&secret=$publicSecretHex")
        assertEquals("Public", parsed.name)
        assertEquals(assertNotNull(Bytes.parseHex(publicSecretHex)), parsed.secret)
        assertNotEquals(ChannelService.hashSecret("Public"), parsed.secret)
    }

    @Test
    fun originalBareNamesNeverDeriveHashtagSecrets() {
        for (name in listOf("Ops", "test")) {
            assertNull(MeshCoreUriParser.parseChannel("meshcore://channel/add?name=$name"), name)
        }
    }

    @Test
    fun originalHashtagNormalizationDistinguishesAbsentFromEmptySecret() {
        val expected = assertNotNull(Bytes.parseHex("9cd8fcf22a47333b591d96a2b848b73f"))
        for (query in listOf("name=%23Test", "name=%23test&secret=")) {
            val parsed = channel("meshcore://channel/add?$query")
            assertEquals("#test", parsed.name, query)
            assertEquals(expected, parsed.secret, query)
            assertEquals(ChannelService.hashSecret("#test"), parsed.secret, query)
        }
    }

    @Test
    fun originalHashtagRegionKeepsTheIndependentGoldenSecret() {
        val parsed = channel("meshcore://channel/add?name=%23avion-testing2&region_scope=testregion")
        assertEquals("testregion", parsed.regionScope)
        assertEquals(assertNotNull(Bytes.parseHex("3976fbac9120f147576900ac90d41dd2")), parsed.secret)
    }

    @Test
    fun originalRegionPaddingIsTrimmedBeforeItsByteCap() {
        assertEquals(30, ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES)
        val longName = "a".repeat(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES + 10)
        val parsed = channel("meshcore://channel/add?name=Ops&secret=$privateSecretHex&region_scope=%20$longName%20")
        assertEquals("a".repeat(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES), parsed.regionScope)
    }

    @Test
    fun originalExportOmitsRegionQueryForInheritAndAllRegions() {
        for (scope in listOf(ChannelFloodScope.Inherit, ChannelFloodScope.AllRegions)) {
            val uri = ChannelService.exportChannelURI("Ops", privateSecret, scope)
            assertFalse(uri.contains("region_scope"), uri)
            assertNull(channel(uri).regionScope)
        }
    }

    @Test
    fun originalHashtagExportRoundTripsItsSecretAndRegion() {
        val name = "#avion-testing2"
        val secret = ChannelService.hashSecret(name)
        assertEquals(assertNotNull(Bytes.parseHex("3976fbac9120f147576900ac90d41dd2")), secret)
        val uri = ChannelService.exportChannelURI(name, secret, ChannelFloodScope.Region("testregion"))
        assertTrue(uri.contains("secret="), uri)
        val parsed = channel(uri)
        assertEquals(name, parsed.name)
        assertEquals(secret, parsed.secret)
        assertEquals("testregion", parsed.regionScope)
    }

    @Test
    fun originalNilRegionDoesNotWriteAndKeepsInherit() = runTest {
        val channel = ChannelDTO(
            radioId = RadioId(UUID.randomUUID()),
            index = 1u,
            name = "Ops",
            secret = Bytes(ByteArray(ProtocolLimits.CHANNEL_SECRET_SIZE) { 0xAB.toByte() }),
        )
        var writes = 0
        val updated = ChannelJoinFloodScopeApplier.applyIfNeeded(channel, null) { _, _ -> writes++ }
        assertEquals(0, writes)
        assertEquals(ChannelFloodScope.Inherit, updated.floodScope)
    }

    private fun channel(uri: String): MeshCoreDeepLink.Channel =
        assertNotNull(MeshCoreUriParser.parseChannel(uri), uri)
}
