// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChannelFloodScopeTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.crypto.CryptoException
import com.meshcoreone.android.core.protocol.crypto.WireCrypto
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class BackupChannelIntegrationTest : BackupRoomTest() {
    // SourceCase: ChannelFloodScopeTests::Codable round-trips all three cases for current envelope format()
    @Test fun allCurrentFloodModesSurviveActualJsonCodec() {
        val codec = AppBackupCodec()
        for (scope in listOf(ChannelFloodScope.Inherit, ChannelFloodScope.AllRegions, ChannelFloodScope.Region("Germany"))) {
            val expected = channel().withFloodScope(scope)
            val actual = codec.parseBackup(codec.encode(envelope(channels = listOf(expected)))).channels.single()
            assertEquals(expected, actual)
            assertEquals(scope, actual.floodScope)
        }
    }

    // SourceCase: ChannelFloodScopeTests::Legacy envelope omitting isFavorite key decodes as false()
    @Test fun omittedFavoriteDefaultsFalseFromNondefaultChannelJson() {
        val original = nondefaultLegacyChannel()
        val actual = decodeLegacyChannelWithout("isFavorite", original)
        assertFalse(actual.isFavorite)
        assertEquals(original.copy(isFavorite = false), actual)
    }

    // SourceCase: ChannelFloodScopeTests::Legacy envelope omitting notificationLevel key decodes as .all()
    @Test fun omittedNotificationDefaultsAllFromMutedChannelJson() {
        val original = nondefaultLegacyChannel()
        val actual = decodeLegacyChannelWithout("notificationLevel", original)
        assertEquals(NotificationLevel.ALL, actual.notificationLevel)
        assertEquals(original.copy(notificationLevel = NotificationLevel.ALL), actual)
    }

    // SourceCase: ChannelFloodScopeTests::Legacy envelope omitting unreadMentionCount key decodes as 0()
    @Test fun omittedMentionsDefaultZeroFromNonzeroChannelJson() {
        val original = nondefaultLegacyChannel()
        val actual = decodeLegacyChannelWithout("unreadMentionCount", original)
        assertEquals(0L, actual.unreadMentionCount)
        assertEquals(original.copy(unreadMentionCount = 0), actual)
    }

    private fun nondefaultLegacyChannel(): ChannelDTO =
        channel().copy(isFavorite = true, notificationLevel = NotificationLevel.MUTED, unreadMentionCount = 5)

    private fun decodeLegacyChannelWithout(key: String, original: ChannelDTO): ChannelDTO {
        val codec = AppBackupCodec()
        assertEquals(original, codec.parseBackup(codec.encode(envelope(channels = listOf(original)))).channels.single())
        val legacy = envelope(channels = listOf(original)).jsonObject().modifyingRow("channels") { it.replacing(key, null) }
        return codec.parseBackup(legacy.compressed()).channels.single()
    }

    private fun secret(value: Int): Bytes = Bytes(ByteArray(16) { value.toByte() })
    private fun channelMessage(index: UByte = 3u): MessageDTO =
        message(contactID = null, index = index, direction = MessageDirection.INCOMING, text = "Praha checkpoint")
            .copy(senderNodeName = "Jan", timestamp = 1_700_000_900u, deduplicationKey = "ch-$index-1700000900-Jan-DEADBEEF")

    @OriginalCase("BackupIntegrationTests::Import onto a different-secret slot keeps the local channel and inserts the backup channel separately()")
    @Test fun distinctSecretCollisionKeepsLocalAndRelocatesForeign() = runBlocking {
        val local = channel(index = 2u, secret = secret(1)).copy(name = "#kosice")
        seed(envelope(channels = listOf(local)))
        val backup = channel(index = 2u, secret = secret(2), id = id(30)).copy(name = "#praha")
        val result = service.importBackup(envelope(devices = listOf(device()), channels = listOf(backup)), store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(0L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(local, db.channels().byId(RADIO.value, local.id)?.toDTO())
        val actual = db.channels().forRadio(RADIO.value).single { it.secret == backup.secret }
        assertEquals("#praha", actual.name); assertNotEquals(2L, actual.index)
    }

    @OriginalCase("BackupIntegrationTests::Channel reconcile: backup channel collides with a different-secret local slot, relocates and carries its message()")
    @Test fun relocatedChannelCarriesMessageAndRewritesLeadingDedupOnly() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 3u, secret = secret(0xB0)).copy(name = "#brno"))))
        val result = service.importBackup(envelope(devices = listOf(device()),
            channels = listOf(channel(index = 3u, secret = secret(0xA0), id = id(30)).copy(name = "#praha")),
            messages = listOf(channelMessage())), store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(0L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        val all = db.channels().forRadio(RADIO.value)
        val brno = all.single { it.secret == secret(0xB0) }
        val praha = all.single { it.secret == secret(0xA0) }
        assertEquals(3L, brno.index); assertEquals("#brno", brno.name)
        assertNotEquals(3L, praha.index); assertEquals("#praha", praha.name)
        val actual = requireNotNull(db.messages().byId(RADIO.value, id(4)))
        assertEquals(praha.index, actual.channelIndex); assertEquals("Praha checkpoint", actual.text)
        assertEquals("ch-${praha.index}-1700000900-Jan-DEADBEEF", actual.deduplicationKey)
        assertTrue(db.messages().newestForChannel(RADIO.value, 3, 10).isEmpty())
    }

    @OriginalCase("BackupIntegrationTests::Re-importing a stale backup never upserts a reconfigured live channel()")
    @Test fun surrogateCollisionCannotOverwriteRotatedSecret() = runBlocking {
        val live = channel(index = 3u, secret = secret(0xB2)).copy(name = "Live Net")
        seed(envelope(channels = listOf(live)))
        val stale = live.copy(name = "Stale Net", secret = secret(0xA1))
        val result = service.importBackup(envelope(devices = listOf(device()), channels = listOf(stale)), store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).inserted)
        val all = db.channels().forRadio(RADIO.value)
        assertEquals(live, all.single { it.secret == live.secret }.toDTO())
        val restored = all.single { it.secret == stale.secret }
        assertNotEquals(live.id, restored.id); assertNotEquals(3L, restored.index)
    }

    @OriginalCase("BackupIntegrationTests::batchInsertChannels reports newly-occupied local slots for draft clearing, excluding merges()")
    @Test fun affectedSlotsIncludeOnlyInsertedAndDroppedOccupancy() = runBlocking {
        assertAffectedSlotPredicates(secretWidth = 32)
    }

    // AndroidOnly: WP-203 Retain the old canonical-width predicate without claiming source-input parity.
    @Test fun canonical16ByteSecretsRetainAffectedSlotPredicates() = runBlocking {
        assertAffectedSlotPredicates(secretWidth = 16)
    }

    private suspend fun assertAffectedSlotPredicates(secretWidth: Int) {
        fun fixtureSecret(value: Int): Bytes = Bytes(ByteArray(secretWidth) { value.toByte() })
        val keep = channel(index = 2u, secret = fixtureSecret(0xC1)).copy(name = "Keep")
        val occupied = channel(index = 4u, secret = fixtureSecret(0xC4), id = id(34)).copy(name = "Occupied")
        seed(envelope(channels = listOf(keep, occupied)))
        val value = envelope(devices = listOf(device()), channels = listOf(
            channel(index = 6u, secret = fixtureSecret(0xC1), id = id(36)).copy(name = "Keep"),
            channel(index = 5u, secret = fixtureSecret(0xD5), id = id(35)).copy(name = "Fresh"),
            channel(index = 4u, secret = fixtureSecret(0xD4), id = id(37)).copy(name = "Collide")))
        val codec = AppBackupCodec()
        val decoded = codec.parseBackup(codec.encode(value))
        assertEquals(value.channels, decoded.channels)
        assertTrue(decoded.channels.all { it.secret.size == secretWidth })
        val result = service.importBackup(decoded, store)
        val affected = result.channelSlotsAffectedByImport.getValue(RADIO)
        assertTrue(affected.contains(5.toUByte()))
        for (slot in listOf(2u, 6u, 4u)) assertFalse(affected.contains(slot.toUByte()))
        val all = db.channels().forRadio(RADIO.value)
        val relocated = all.single { it.secret == fixtureSecret(0xD4) }
        assertNotEquals(4L, relocated.index)
        assertTrue(affected.contains(relocated.index.toUByte()))
        assertEquals(setOf(5.toUByte(), relocated.index.toUByte()), affected.toSet())
        assertEquals(2L, result.count(BackupModelKind.CHANNELS).inserted)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(4, all.size)
        assertTrue(all.all { it.secret.size == secretWidth })
        assertEquals(keep, all.single { it.secret == keep.secret }.toDTO())
        assertEquals(occupied, all.single { it.secret == occupied.secret }.toDTO())
        assertEquals("Fresh", all.single { it.index == 5L }.name)
        assertEquals("Collide", relocated.name)
    }

    // AndroidOnly: WP-203 Raw backup bytes and the existing fixed-width wire projection are distinct contracts.
    @Test fun backupPreservesFullSecretsWhileWireBuilderProjectsOnlyExisting16BytePrefix() {
        val codec = AppBackupCodec()
        for (width in listOf(16, 32)) {
            val raw = ByteArray(width) { if (it < 16) 0xC1.toByte() else 0xD4.toByte() }
            val value = envelope(channels = listOf(channel(index = 4u, secret = Bytes(raw))))
            val encodedSecret = value.jsonObject().getValue("channels").jsonArray.single().jsonObject
                .getValue("secret").jsonPrimitive.content
            assertEquals(Base64.getEncoder().encodeToString(raw), encodedSecret)
            assertArrayEquals(raw, Base64.getDecoder().decode(encodedSecret))
            val restored = codec.parseBackup(codec.encode(value)).channels.single()
            assertEquals(width, restored.secret.size)
            assertArrayEquals(raw, restored.secret.toByteArray())
            val packet = PacketBuilder.setChannel(restored.index, restored.name, restored.secret)
            assertEquals(50, packet.size)
            assertArrayEquals(raw.copyOfRange(0, 16), packet.toByteArray().copyOfRange(34, 50))
        }
        val failure = assertThrows(CryptoException.SecretTooShort::class.java) {
            WireCrypto.encryptAes128EcbZeroPadded(Bytes.of(0), Bytes(ByteArray(15)))
        }
        assertEquals(15, failure.actual)
    }

    @OriginalCase("BackupIntegrationTests::Exporting a channel with an unmigrated notification level does not migrate the live row()")
    @Test fun exportLegacyNotificationProjectionDoesNotMutateLiveRow() = runBlocking {
        db.channels().insert(channel(index = 1u, secret = secret(0x33)).toEntity().copy(notificationLevelRawValue = -1, legacyIsMuted = true))
        val exported = service.exportEnvelope(store).channels.single()
        assertEquals(-1L, db.channels().forRadio(RADIO.value).single().notificationLevelRawValue)
        assertEquals(NotificationLevel.MUTED, exported.notificationLevel)
    }

    @OriginalCase("BackupIntegrationTests::Channel floodScope survives a fresh-insert export/import round-trip()")
    @Test fun freshScopeBothInheritAndRegionRoundTrip() = runBlocking {
        val regional = channel(index = 1u, secret = secret(0x55), id = id(31)).withFloodScope(ChannelFloodScope.Region("US"))
        seed(envelope(channels = listOf(channel(), regional)))
        val exported = AppBackupCodec().parseBackup(service.export(store).data)
        db.channels().clearRadio(RADIO.value)
        val result = service.importBackup(exported, store)
        assertEquals(2L, result.count(BackupModelKind.CHANNELS).inserted)
        val all = db.channels().forRadio(RADIO.value).map { it.toDTO() }
        assertEquals(ChannelFloodScope.Inherit, all.single { it.index == 0.toUByte() }.floodScope)
        assertEquals(ChannelFloodScope.Region("US"), all.single { it.index == 1.toUByte() }.floodScope)
    }

    @OriginalCase("BackupIntegrationTests::Channel reconcile: same secret at same slot merges metadata, index unchanged()")
    @Test fun sameSecretSameSlotMergesAndPreservesMaxDate() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 2u, secret = secret(0x44)))))
        val backup = channel(index = 2u, secret = secret(0x44), id = id(30)).copy(lastMessageDate = AT.plusSeconds(950))
        val msg = channelMessage(2u).copy(createdAt = java.time.Instant.ofEpochSecond(1_600_000_000))
        val result = service.importBackup(envelope(channels = listOf(backup), messages = listOf(msg)), store)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        val all = db.channels().forRadio(RADIO.value)
        assertEquals(1, all.size); assertEquals(2L, all.single().index); assertEquals(backup.secret, all.single().secret)
        assertEquals(backup.lastMessageDate, all.single().lastMessageDate?.toInstant())
        assertEquals(2L, db.messages().byId(RADIO.value, id(4))?.channelIndex)
    }

    @OriginalCase("BackupIntegrationTests::Channel reconcile: same secret on a different slot remaps the message, no duplicate channel()")
    @Test fun sameSecretDifferentSlotRemapsWithoutInsert() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 5u, secret = secret(0x55)).copy(name = "#praha"))))
        val result = service.importBackup(envelope(channels = listOf(channel(index = 3u, secret = secret(0x55), id = id(30))),
            messages = listOf(channelMessage())), store)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        assertEquals(1, db.channels().forRadio(RADIO.value).size); assertEquals(5L, db.channels().forRadio(RADIO.value).single().index)
        assertEquals(5L, db.messages().byId(RADIO.value, id(4))?.channelIndex)
    }

    @OriginalCase("BackupIntegrationTests::Channel reconcile: public channel merges into local public channel without relocation()")
    @Test fun publicEmptySecretMergesBySlot() = runBlocking {
        seed(envelope(channels = listOf(channel())))
        val result = service.importBackup(envelope(channels = listOf(channel(id = id(30)).copy(lastMessageDate = AT.plusSeconds(1100))),
            messages = listOf(channelMessage(0u))), store)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(1L, result.count(BackupModelKind.CHANNELS).skipped)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        assertEquals(1, db.channels().forRadio(RADIO.value).size); assertEquals(0L, db.channels().forRadio(RADIO.value).single().index)
        assertEquals(0L, db.messages().byId(RADIO.value, id(4))?.channelIndex)
    }

    @OriginalCase("BackupIntegrationTests::Channel reconcile: re-importing a relocated channel skips the message the second time()")
    @Test fun relocationDedupRewriteIsIdempotent() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 3u, secret = secret(0xB2)))))
        val value = envelope(devices = listOf(device()), channels = listOf(channel(index = 3u, secret = secret(0xA2), id = id(30))),
            messages = listOf(channelMessage()))
        assertEquals(1L, service.importBackup(value, store).count(BackupModelKind.MESSAGES).inserted)
        val result = service.importBackup(value, store)
        assertEquals(0L, result.count(BackupModelKind.MESSAGES).inserted); assertEquals(1L, result.count(BackupModelKind.MESSAGES).skipped)
        assertNotNull(db.messages().byId(RADIO.value, id(4)))
        assertEquals(1, service.exportEnvelope(store).messages.size)
    }

    @OriginalCase("BackupIntegrationTests::Channel reconcile: backup channel with no free slot is dropped along with its messages()")
    @Test fun fullRadioDropsForeignChannelAndItsMessage() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 0u, secret = secret(0x10)), channel(index = 1u, secret = secret(0x11), id = id(31)))))
        val result = service.importBackup(envelope(devices = listOf(device().copy(maxChannels = 2u)),
            channels = listOf(channel(index = 1u, secret = secret(0xA4), id = id(30))), messages = listOf(channelMessage(1u))), store)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).inserted); assertEquals(1L, result.count(BackupModelKind.CHANNELS).dropped)
        assertEquals(0L, result.count(BackupModelKind.MESSAGES).inserted)
        assertEquals(2, db.channels().forRadio(RADIO.value).size); assertFalse(db.channels().forRadio(RADIO.value).any { it.secret == secret(0xA4) })
        assertNull(db.messages().byId(RADIO.value, id(4)))
    }

    @OriginalCase("BackupIntegrationTests::Dropped channels and their messages are accounted as dropped, not already-here()")
    @Test fun noSlotIsDroppedNotAlreadyPresent() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 1u, secret = secret(0x11)))))
        val result = service.importBackup(envelope(devices = listOf(device().copy(maxChannels = 2u)),
            channels = listOf(channel(index = 1u, secret = secret(0x22), id = id(30)))), store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).dropped)
        assertEquals(0L, result.count(BackupModelKind.CHANNELS).skipped)
        assertTrue(result.channelSlotsAffectedByImport.getValue(RADIO).contains(1.toUByte()))
    }

    @OriginalCase("BackupIntegrationTests::Full import accounts a no-slot channel's messages as dropped and the manifest balances()")
    @Test fun droppedMessageCountsBalanceManifest() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 0u, secret = secret(0x10)), channel(index = 1u, secret = secret(0x11), id = id(31)))))
        val value = envelope(devices = listOf(device().copy(maxChannels = 2u)),
            channels = listOf(channel(index = 1u, secret = secret(0xA4), id = id(30))),
            messages = listOf(channelMessage(1u), channelMessage(1u).copy(id = id(40), text = "lost two", timestamp = 1_700_002_100u,
                deduplicationKey = "ch-1-1700002100-Eva-AABBCCDE")))
        val result = service.importBackup(value, store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).dropped); assertEquals(2L, result.count(BackupModelKind.MESSAGES).dropped)
        val count = result.count(BackupModelKind.MESSAGES)
        assertEquals(value.manifest.messageCount, count.inserted + count.merged + count.skipped + count.dropped)
    }

    @OriginalCase("BackupIntegrationTests::Channel reconcile: relocation reserves slot 0 for the public channel()")
    @Test fun relocationNeverConsumesReservedPublicSlot() = runBlocking {
        seed(envelope(channels = listOf(channel(index = 1u, secret = secret(0xB6)))))
        val result = service.importBackup(envelope(devices = listOf(device().copy(maxChannels = 3u)),
            channels = listOf(channel(index = 1u, secret = secret(0xA6), id = id(30)))), store)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).inserted)
        assertEquals(2L, db.channels().forRadio(RADIO.value).single { it.secret == secret(0xA6) }.index)
        assertTrue(db.channels().forIndex(RADIO.value, 0).isEmpty())
    }
}
