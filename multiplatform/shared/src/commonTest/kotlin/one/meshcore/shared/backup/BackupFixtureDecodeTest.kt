package one.meshcore.shared.backup

import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.NotificationLevel
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin side of the cross-platform backup freeze. Decodes the checked-in vectors under
 * `fixtures/backup/` and asserts the same invariants Swift's `BackupFixtureTests` asserts. Import
 * (decode) direction only; byte-identical encoding is Phase 6.
 */
@OptIn(ExperimentalEncodingApi::class)
class BackupFixtureDecodeTest {
    private val radioId = "A0000000-0000-0000-0000-000000000001"
    private val devicePublicKey = ByteArray(32) { 0x11 }
    private val channelSecret = ByteArray(16) { 0x22 }
    private val contactPublicKey = Base64.decode("q6urq6urq6urq6urq6urq6urq6urq6urq6urq6urq6s=")
    private val dmDate = 1_700_000_100.0

    // full-envelope.json

    @Test
    fun fullEnvelopeDecodesEveryFamilyWithReconciliationKeysIntact() {
        val envelope = decodeBackupEnvelope(loadBackupFixture("full-envelope.json"))

        assertEquals(AppBackupEnvelope.CURRENT_VERSION, envelope.version)
        assertTrue(envelope.manifest.validate(envelope), "manifest counts must match array sizes")

        assertEquals(1, envelope.devices.size)
        assertEquals(1, envelope.contacts.size)
        assertEquals(1, envelope.channels.size)
        assertEquals(2, envelope.messages.size)
        assertEquals(1, envelope.messageRepeats.size)
        assertEquals(1, envelope.reactions.size)
        assertEquals(1, envelope.roomMessages.size)
        assertEquals(1, envelope.remoteNodeSessions.size)
        assertEquals(1, envelope.savedTracePaths.size)
        assertEquals(1, envelope.blockedChannelSenders.size)
        assertEquals(1, envelope.nodeStatusSnapshots.size)

        val device = envelope.devices.first()
        assertEquals(radioId, device.radioID.value)
        assertContentEquals(devicePublicKey, device.publicKey)
        // Associated-value ConnectionMethod decodes back to its WiFi case; no BLE method present.
        assertEquals(1, device.connectionMethods.size)
        val wifi = assertNotNull(device.connectionMethods.first() as? ConnectionMethod.WiFi)
        assertEquals("192.168.1.50", wifi.host)
        assertEquals(5000, wifi.port)
        assertEquals("Base Camp", wifi.displayName)
        assertTrue(device.connectionMethods.none { it is ConnectionMethod.Bluetooth })
        assertEquals(listOf("US915", "EU868"), device.knownRegions)

        val channel = envelope.channels.first()
        assertEquals(radioId, channel.radioID.value)
        assertEquals(ChannelFloodScope.Region("US"), channel.floodScope)
        assertEquals(NotificationLevel.MENTIONS_ONLY, channel.notificationLevel)

        val contact = envelope.contacts.first()
        assertContentEquals(contactPublicKey, contact.publicKey)
        assertEquals("Field Ops", contact.nickname)

        assertEquals("👍", envelope.reactions.first().emoji)

        assertEquals("US", envelope.userDefaults?.regionSelection?.countryCode)
        assertEquals(listOf("👍", "❤️"), envelope.userDefaults?.frequentEmojis)
    }

    // unicode-edge.json

    @Test
    fun unicodeEdgePreservesNonAsciiTextAndEmptyVsMissingNickname() {
        val envelope = decodeBackupEnvelope(loadBackupFixture("unicode-edge.json"))

        assertEquals(setOf("李明", "Renée"), envelope.contacts.map { it.name }.toSet())

        // Empty string was emitted (present) for one contact; the key was omitted (null) for the other.
        val emptyNickname = assertNotNull(envelope.contacts.firstOrNull { it.name == "李明" })
        assertEquals("", emptyNickname.nickname)
        val missingNickname = assertNotNull(envelope.contacts.firstOrNull { it.name == "Renée" })
        assertNull(missingNickname.nickname)

        assertEquals("🚨緊急", envelope.channels.first().name)
        val message = envelope.messages.first()
        assertEquals("火事です🔥 evacuate now 👨‍👩‍👧‍👦", message.text)
        assertEquals("山田", message.senderNodeName)
    }

    // legacy-minimal.json

    @Test
    fun legacyMinimalDecodesThroughEveryFallback() {
        val envelope = decodeBackupEnvelope(loadBackupFixture("legacy-minimal.json"))
        assertTrue(envelope.manifest.validate(envelope))

        // Message: missing sortDate falls back to createdAt.
        val message = envelope.messages.first()
        assertEquals(message.createdAt, message.sortDate)
        assertEquals(dmDate, message.createdAt)

        // Channel: mode key stripped but named regionScope remains, so the fallback reconstructs
        // Region(name). The other stripped keys fall back to their documented defaults.
        val channel = envelope.channels.first()
        assertEquals(ChannelFloodScope.Region("Germany"), channel.floodScope)
        assertEquals(0, channel.unreadMentionCount)
        assertEquals(NotificationLevel.ALL, channel.notificationLevel)
        assertEquals(false, channel.isPinned)
        // Non-stripped values still decode from the wire.
        assertEquals(4, channel.unreadCount)
        assertContentEquals(channelSecret, channel.secret)
    }
}
