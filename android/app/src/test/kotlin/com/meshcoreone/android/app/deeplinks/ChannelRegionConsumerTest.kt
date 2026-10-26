// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.MeshCoreUriParser
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.feature.chats.channels.ChannelLink
import com.meshcoreone.android.feature.chats.channels.ChannelSheetsStateHolder
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.chats.list.MeshCoreLinkParser
import java.net.URLEncoder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRegionConsumerTest : RoomProcessTest() {
    private enum class Entry { EXTERNAL, CHAT, QR }
    private val vectors = listOf(
        null to null,
        "" to null,
        " \n " to null,
        ("  " + "a".repeat(40) + "  ") to "a".repeat(30),
        ("a".repeat(29) + "e\u0301") to "a".repeat(29),
        ("a".repeat(27) + "e\u0301") to ("a".repeat(27) + "e\u0301"),
    )

    @Test fun externalRoutesAndActualJoinPersistOnlyTheThirtyByteGraphemePrefix() = runTest { joinRegions(Entry.EXTERNAL) }
    @Test fun chatRoutesAndActualJoinPersistOnlyTheThirtyByteGraphemePrefix() = runTest { joinRegions(Entry.CHAT) }
    @Test fun qrParserAndActualJoinPersistOnlyTheThirtyByteGraphemePrefix() = runTest { joinRegions(Entry.QR) }

    @Test fun absentAndEmptySecretsKeepHashtagDerivationWithAbsentAndEmptyRegions() {
        for (secret in listOf("", "&secret=")) {
            for (region in listOf("", "&region_scope=")) {
                val uri = "meshcore://channel/add?name=%23avion-testing2$secret$region"
                val external = assertNotNull(MeshCoreUriParser.parseChannel(uri))
                val chatAndQr = assertNotNull(MeshCoreLinkParser.parseChannelURL(uri))
                assertEquals("#avion-testing2", external.name)
                assertEquals(external.name, chatAndQr.name)
                assertEquals(ChannelService.hashSecret("#avion-testing2"), external.secret)
                assertEquals(external.secret, chatAndQr.secret)
                assertNull(external.regionScope)
                assertNull(chatAndQr.regionScope)
            }
        }
    }

    private suspend fun TestScope.joinRegions(entry: Entry) {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x20) listOf(Bytes.of(0)) else null }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radioId = assertNotNull(h.appState.currentRadioId)
            val feature = h.container.chatsFeature
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            val chat = ChatLinkRouter(feature.list.links, backgroundScope)
            val firmware = h.links.radios.single()
            for ((index, vector) in vectors.withIndex()) {
                val (rawRegion, expected) = vector
                val name = "#region-$index"
                val secret = Bytes(ByteArray(16) { (index + 1).toByte() })
                val uri = "meshcore://channel/add?name=%23region-$index&secret=${secret.hexString}" +
                    (rawRegion?.let { "&region_scope=${URLEncoder.encode(it, Charsets.UTF_8)}" } ?: "")
                val link = when (entry) {
                    Entry.EXTERNAL, Entry.CHAT -> {
                        if (entry == Entry.EXTERNAL) external.routeExternal(uri) else assertTrue(chat.route(uri))
                        testScheduler.runCurrent()
                        val request = assertNotNull(navigation.state.value.pendingChannelLink)
                        navigation.clearPendingChannelLink()
                        ChannelLink(request.name, request.secret, request.regionScope, request.hasHashtagSecretMismatch)
                    }
                    Entry.QR -> {
                        // This is the parser and join seam used by AppChannelsContent.onDecoded, not camera evidence.
                        val parsed = assertNotNull(MeshCoreLinkParser.parseChannelURL(uri))
                        ChannelLink(parsed.name, parsed.secret, parsed.regionScope)
                    }
                }
                assertEquals(name, link.name)
                assertEquals(secret, link.secret)
                assertEquals(expected, link.regionScope, "$entry input=$rawRegion")
                assertTrue((link.regionScope?.toByteArray(Charsets.UTF_8)?.size ?: 0) <= 30)
                val holder = ChannelSheetsStateHolder(radioId, feature.maximumChannels(), feature.channels)
                holder.load()
                assertNotNull(holder.joinLink(link))
                val persisted = store.fetchChannels(radioId).single { it.name == name }
                assertEquals(secret, persisted.secret)
                assertEquals(expected?.let(ChannelFloodScope::Region) ?: ChannelFloodScope.Inherit, persisted.floodScope)
                assertEquals(PacketBuilder.setChannel(persisted.index, name, secret), firmware.sent.last { it[0].toInt() == 0x20 })
            }
            assertEquals(vectors.size, firmware.sent.count { it[0].toInt() == 0x20 })
        } finally { h.container.close() }
    }
}
