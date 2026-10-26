// PortedFrom: MC1Services/Tests/MC1ServicesTests/BackupIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class BackupFieldIntegrationTest : BackupRoomTest() {
    @OriginalCase("BackupIntegrationTests::Unmodeled contact type byte (0x04) survives DTO encode → decode round-trip()")
    @Test fun unmodeledContactTypeIsPreservedNotCollapsed() {
        val actual = decodeContact(encodeContact(contact().copy(typeRawValue = 4u)))
        assertEquals(4.toUByte(), actual.typeRawValue)
        assertEquals(com.meshcoreone.android.core.protocol.model.ContactType.CHAT, actual.type)
    }

    @OriginalCase("BackupIntegrationTests::Unmodeled contact type byte (0x04) survives full backup export → import()")
    @Test fun unmodeledContactTypeSurvivesRealPipeline() = runBlocking {
        seed(envelope(contacts = listOf(contact().copy(typeRawValue = 4u, name = "FutureType"))))
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        db.contacts().clearRadio(RADIO.value)
        service.importBackup(backup, store)
        val actual = db.contacts().forRadio(RADIO.value).single().toDTO()
        assertEquals(4.toUByte(), actual.typeRawValue)
        assertEquals(com.meshcoreone.android.core.protocol.model.ContactType.CHAT, actual.type)
    }

    @OriginalCase("BackupIntegrationTests::Device knownRegions survives DTO encode → decode round-trip()")
    @Test fun orderedKnownRegionsAreRequiredWireFields() {
        val expected = device().copy(knownRegions = SnapshotList.of("US915", "EU868"))
        assertEquals(listOf("US915", "EU868"), decodeDevice(encodeDevice(expected)).knownRegions)
        assertThrows(BackupValueException::class.java) { decodeDevice(encodeDevice(expected).replacing("knownRegions", null)) }
    }

    @OriginalCase("BackupIntegrationTests::Device knownRegions survives full backup export → import into a fresh store()")
    @Test fun targetedKnownRegionWriterFeedsActualBackup() = runBlocking {
        seed(envelope(devices = listOf(device())))
        store.addDeviceKnownRegion(RADIO, "US915"); store.addDeviceKnownRegion(RADIO, "EU868")
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        assertEquals(listOf("US915", "EU868"), backup.devices.single().knownRegions)
        db.devices().delete(id(1))
        val result = service.importBackup(backup, store)
        assertEquals(1L, result.count(BackupModelKind.DEVICES).inserted)
        assertEquals(listOf("US915", "EU868"), db.devices().forRadio(RADIO.value).single().knownRegions)
        assertNotEquals(id(1), db.devices().forRadio(RADIO.value).single().id)
    }

    @OriginalCase("BackupIntegrationTests::Device appliedRadioPresetID survives DTO encode → decode round-trip()")
    @Test fun presetWireFieldRetainsExplicitValue() { assertEquals("br", decodeDevice(encodeDevice(device().copy(appliedRadioPresetID = "br"))).appliedRadioPresetID) }

    @OriginalCase("BackupIntegrationTests::Legacy device envelope without appliedRadioPresetID decodes it as nil()")
    @Test fun omittedPresetIsGenuinelyNull() {
        assertNull(decodeDevice(encodeDevice(device().copy(appliedRadioPresetID = "br")).replacing("appliedRadioPresetID", null)).appliedRadioPresetID)
    }

    @OriginalCase("BackupIntegrationTests::redactedForBackup nils appliedRadioPresetID()")
    @Test fun sourceRedactionClearsPreset() { assertNull(device().copy(appliedRadioPresetID = "br").redactedForBackup().appliedRadioPresetID) }

    @OriginalCase("BackupIntegrationTests::Node status packet-type counters survive DTO encode → decode round-trip()")
    @Test fun packetCountersRoundTripTypedWidths() {
        val dto = counters()
        assertEquals(dto, decodeSnapshot(encodeSnapshot(dto)))
        assertEquals(100u, decodeSnapshot(encodeSnapshot(dto)).sentDirect)
        assertEquals(22u, decodeSnapshot(encodeSnapshot(dto)).floodDuplicates)
    }

    @OriginalCase("BackupIntegrationTests::Legacy snapshot envelope without packet-type counters decodes them as nil()")
    @Test fun omittedCountersAreNotFabricatedZeros() {
        var json = encodeSnapshot(counters())
        for (key in listOf("sentDirect", "sentFlood", "receivedDirect", "receivedFlood", "directDuplicates", "floodDuplicates")) json = json.replacing(key, null)
        val dto = decodeSnapshot(json)
        assertNull(dto.sentDirect); assertNull(dto.sentFlood); assertNull(dto.receivedDirect); assertNull(dto.receivedFlood)
        assertNull(dto.directDuplicates); assertNull(dto.floodDuplicates); assertEquals(3800.toUShort(), dto.batteryMillivolts)
    }

    @OriginalCase("BackupIntegrationTests::Node location survives DTO encode → decode round-trip()")
    @Test fun nodeLocationWireFieldsRoundTrip() {
        val actual = decodeSnapshot(encodeSnapshot(snapshot().copy(latitude = 37.7749, longitude = -122.4194)))
        assertEquals(37.7749, requireNotNull(actual.latitude), 0.0); assertEquals(-122.4194, requireNotNull(actual.longitude), 0.0)
    }

    @OriginalCase("BackupIntegrationTests::Legacy snapshot envelope without location decodes it as nil()")
    @Test fun omittedNodeLocationDoesNotInventNullIsland() {
        val actual = decodeSnapshot(encodeSnapshot(snapshot().copy(latitude = 37.7749, longitude = -122.4194))
            .replacing("latitude", null).replacing("longitude", null))
        assertNull(actual.latitude); assertNull(actual.longitude); assertEquals(3800.toUShort(), actual.batteryMillivolts)
    }

    @OriginalCase("BackupIntegrationTests::Node altitude survives DTO encode → decode round-trip()")
    @Test fun nodeAltitudeIsIndependentWireField() {
        val expected = snapshot().copy(latitude = 37.7749, longitude = -122.4194, altitude = 42.0)
        val codec = AppBackupCodec()
        val actual = codec.parseBackup(codec.encode(envelope(snapshots = listOf(expected)))).nodeStatusSnapshots.single()
        assertEquals(expected, actual)
        assertEquals(42.0, requireNotNull(actual.altitude), 0.0)
        assertEquals(37.7749, requireNotNull(actual.latitude), 0.0)
        assertEquals(-122.4194, requireNotNull(actual.longitude), 0.0)
    }

    @OriginalCase("BackupIntegrationTests::Legacy snapshot envelope without altitude decodes it as nil()")
    @Test fun omittedAltitudeDoesNotDiscardKnownLocation() {
        val actual = decodeSnapshot(encodeSnapshot(snapshot().copy(latitude = 37.7749, longitude = -122.4194, altitude = 42.0))
            .replacing("altitude", null))
        assertNull(actual.altitude); assertEquals(37.7749, requireNotNull(actual.latitude), 0.0)
        assertEquals(-122.4194, requireNotNull(actual.longitude), 0.0)
    }

    @OriginalCase("BackupIntegrationTests::Node status packet-type counters survive full backup export → import()")
    @Test fun packetCountersSurviveGenuineNativeRestore() = runBlocking {
        val dto = counters()
        val result = service.importBackup(envelope(snapshots = listOf(dto)), store)
        assertEquals(1L, result.count(BackupModelKind.NODE_STATUS_SNAPSHOTS).inserted)
        assertEquals(dto, db.nodeSnapshots().history(dto.nodePublicKey).single().toDTO())
        assertEquals(dto, AppBackupCodec().parseBackup(service.export(store).data).nodeStatusSnapshots.single())
    }

    @OriginalCase("BackupIntegrationTests::Message.regionScope round-trips through full export/import()")
    @Test fun scopeRegionIsOpaqueInNativePipeline() = runBlocking {
        val dto = message().copy(regionScope = "Germany", deduplicationKey = "region-scope-roundtrip")
        seed(envelope(messages = listOf(dto)))
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        db.messages().clearRadio(RADIO.value)
        service.importBackup(backup, store)
        assertEquals("Germany", db.messages().byId(RADIO.value, dto.id)?.regionScope)
    }

    @OriginalCase("BackupIntegrationTests::MessageDTO Codable: regionScope set decodes round-trip()")
    @Test fun explicitRegionScopeWireRoundTrip() { assertEquals("Bavaria", decodeMessage(encodeMessage(message().copy(regionScope = "Bavaria"))).regionScope) }

    @OriginalCase("BackupIntegrationTests::Legacy MessageDTO envelope without regionScope decodes as nil()")
    @Test fun omittedRegionScopeIsNotDefaulted() { assertNull(decodeMessage(encodeMessage(message()).replacing("regionScope", null)).regionScope) }

    @OriginalCase("BackupIntegrationTests::Message(dto:) forwards regionScope verbatim through DTO to model()")
    @Test fun nativeModelForwardingRetainsKnownAndNullRegions() = runBlocking {
        val known = message().copy(regionScope = "USA")
        val nil = message(id = id(40)).copy(regionScope = null)
        service.importBackup(envelope(messages = listOf(known, nil)), store)
        assertEquals("USA", db.messages().byId(RADIO.value, known.id)?.regionScope)
        assertNull(db.messages().byId(RADIO.value, nil.id)?.regionScope)
    }

    @OriginalCase("BackupIntegrationTests::Message.regionScopeMatches round-trips through full export/import()")
    @Test fun ambiguousScopesSurviveRealPipelineWithoutInventingSingleScope() = runBlocking {
        val dto = message().copy(regionScope = null, regionScopeMatches = SnapshotList.of("de-by", "de-hh"), deduplicationKey = "region-scope-matches-roundtrip")
        seed(envelope(messages = listOf(dto)))
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        db.messages().clearRadio(RADIO.value)
        service.importBackup(backup, store)
        assertNull(db.messages().byId(RADIO.value, dto.id)?.regionScope)
        assertEquals(listOf("de-by", "de-hh"), db.messages().byId(RADIO.value, dto.id)?.regionScopeMatches)
    }

    @OriginalCase("BackupIntegrationTests::MessageDTO Codable: regionScopeMatches set decodes round-trip()")
    @Test fun orderedScopeMatchesWireRoundTrip() {
        assertEquals(listOf("de-by", "de-hh"), decodeMessage(encodeMessage(message().copy(regionScopeMatches = SnapshotList.of("de-by", "de-hh")))).regionScopeMatches)
    }

    @OriginalCase("BackupIntegrationTests::Legacy MessageDTO envelope without regionScopeMatches decodes as empty()")
    @Test fun legacyMissingScopeMatchesRemainEmptyEvenWithRegion() {
        val actual = decodeMessage(encodeMessage(message().copy(regionScope = "Germany")).replacing("regionScopeMatches", null))
        assertTrue(actual.regionScopeMatches.isEmpty()); assertEquals("Germany", actual.regionScope)
    }

    @OriginalCase("BackupIntegrationTests::Message(dto:) forwards regionScopeMatches verbatim through DTO to model()")
    @Test fun scopeMatchesNativeColumnsPreserveExactArray() = runBlocking {
        val dto = message().copy(regionScopeMatches = SnapshotList.of("de-by", "de-hh"))
        service.importBackup(envelope(messages = listOf(dto)), store)
        assertEquals(dto.regionScopeMatches, db.messages().byId(RADIO.value, dto.id)?.regionScopeMatches)
    }

    @OriginalCase("BackupIntegrationTests::MessageDTO Codable: failureSeen true round-trips()")
    @Test fun messageFailureSeenWireRoundTrip() { assertTrue(decodeMessage(encodeMessage(message().copy(failureSeen = true, status = MessageStatus.FAILED))).failureSeen) }

    @OriginalCase("BackupIntegrationTests::Legacy MessageDTO envelope without failureSeen decodes as false()")
    @Test fun legacyMessageFailureSeenDefaultsFalse() {
        for (status in listOf(MessageStatus.SENT, MessageStatus.FAILED)) {
            val original = message(text = "Legacy").copy(status = status, failureSeen = true)
            val legacy = envelope(messages = listOf(original)).jsonObject().modifyingRow("messages") { it.replacing("failureSeen", null) }
            val actual = AppBackupCodec().parseBackup(legacy.compressed()).messages.single()
            assertFalse(actual.failureSeen)
            assertEquals(original.copy(failureSeen = false), actual)
            assertEquals(MessageDirection.OUTGOING, actual.direction)
        }
    }

    @OriginalCase("BackupIntegrationTests::Message(dto:) forwards failureSeen verbatim through DTO to model()")
    @Test fun failureSeenNativeColumnPreserved() = runBlocking {
        service.importBackup(envelope(messages = listOf(message().copy(failureSeen = true))), store)
        assertTrue(requireNotNull(db.messages().byId(RADIO.value, id(4))).failureSeen)
    }

    @OriginalCase("BackupIntegrationTests::RoomMessageDTO Codable: failureSeen true round-trips()")
    @Test fun roomFailureSeenWireRoundTrip() { assertTrue(decodeRoomMessage(encodeRoomMessage(roomMessage().copy(isFromSelf = true, statusRawValue = 4, failureSeen = true))).failureSeen) }

    @OriginalCase("BackupIntegrationTests::Legacy RoomMessageDTO envelope without failureSeen decodes as false()")
    @Test fun legacyRoomFailureSeenDefaultsFalse() {
        val original = roomMessage().copy(isFromSelf = true, statusRawValue = 4, failureSeen = true)
        val legacy = envelope(roomMessages = listOf(original)).jsonObject().modifyingRow("roomMessages") { it.replacing("failureSeen", null) }
        val actual = AppBackupCodec().parseBackup(legacy.compressed()).roomMessages.single()
        assertFalse(actual.failureSeen)
        assertTrue(actual.isFromSelf)
        assertEquals(4L, actual.statusRawValue)
        assertEquals(original.copy(failureSeen = false), actual)
    }

    @OriginalCase("BackupIntegrationTests::RoomMessage(dto:) forwards failureSeen verbatim through DTO to model()")
    @Test fun roomFailureSeenNativeColumnPreserved() = runBlocking {
        service.importBackup(envelope(sessions = listOf(session()), roomMessages = listOf(roomMessage().copy(isFromSelf = true, statusRawValue = 4, failureSeen = true))), store)
        assertTrue(requireNotNull(db.roomMessages().byId(RADIO.value, id(7))).failureSeen)
    }

    @OriginalCase("BackupIntegrationTests::MessageDTO Codable: sortDate distinct from createdAt round-trips()")
    @Test fun sortAndCreatedDatesAreIndependentWireValues() {
        val dto = message().copy(sortDate = java.time.Instant.ofEpochSecond(1_600_000_000))
        val actual = decodeMessage(encodeMessage(dto))
        assertEquals(dto.sortDate, actual.sortDate); assertNotEquals(actual.sortDate, actual.createdAt)
    }

    @OriginalCase("BackupIntegrationTests::Legacy MessageDTO envelope without sortDate falls back to createdAt()")
    @Test fun omittedSortDateHasOnlySourceFallback() {
        val actual = decodeMessage(encodeMessage(message()).replacing("sortDate", null))
        assertEquals(actual.createdAt, actual.sortDate)
    }

    @OriginalCase("BackupIntegrationTests::Message(dto:) forwards sortDate verbatim through DTO to model()")
    @Test fun originalEarlierSortDateForwardsVerbatimThroughCodecAndRoom() = runBlocking {
        val original = message(text = "Forward").copy(createdAt = java.time.Instant.ofEpochSecond(1_700_000_000),
            sortDate = java.time.Instant.ofEpochSecond(1_600_000_000))
        val codec = AppBackupCodec()
        val decoded = codec.parseBackup(codec.encode(envelope(messages = listOf(original))))
        service.importBackup(decoded, store)
        store.warmUp()
        val actual = requireNotNull(db.messages().byId(RADIO.value, original.id)).toDTO()
        assertEquals(original, actual)
        assertEquals(java.time.Instant.ofEpochSecond(1_600_000_000), actual.sortDate)
        assertEquals(java.time.Instant.ofEpochSecond(1_700_000_000), actual.createdAt)
        assertTrue(actual.sortDate < actual.createdAt)
    }

    @Test fun exactLegacyDatesRemainDistinctAfterRestoreAndWarmUp() = runBlocking {
        val dto = message().copy(timestamp = 1_700_000_000u, createdAt = java.time.Instant.ofEpochSecond(1_704_067_200),
            sortDate = java.time.Instant.ofEpochSecond(1_704_070_800))
        service.importBackup(envelope(messages = listOf(dto)), store)
        store.warmUp()
        val actual = requireNotNull(db.messages().byId(RADIO.value, dto.id)).toDTO()
        assertEquals(dto.sortDate, actual.sortDate); assertEquals(dto.createdAt, actual.createdAt)
        assertNotEquals(actual.sortDate, actual.createdAt); assertEquals(1_700_000_000u, actual.timestamp)
    }

    @OriginalCase("BackupIntegrationTests::Fully-populated MessageDTO survives encode/decode with every field distinct()")
    @Test fun allFortyMessageWireFieldsAreAccountedFor() {
        val dto = message().copy(channelIndex = 7u, text = "Every field set", timestamp = 1_700_000_001u,
            sortDate = java.time.Instant.ofEpochSecond(1_600_000_000), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.SIGNED_PLAIN, ackCode = 0xDEAD_BEEFu, pathLength = 5u,
            snr = 12.5, pathNodes = Bytes.of(1, 2, 3), senderKeyPrefix = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF),
            senderNodeName = "Bob", isRead = true, replyToID = id(41), roundTripTime = 432u, heardRepeats = 3,
            sendCount = 2, retryAttempt = 1, maxRetryAttempts = 4, deduplicationKey = "dedup-key",
            linkPreviewURL = "https://example.com", linkPreviewTitle = "Example", linkPreviewImageData = Bytes.of(0x10, 0x20),
            linkPreviewIconData = Bytes.of(0x30, 0x40), linkPreviewFetched = true, containsSelfMention = true, mentionSeen = true,
            timestampCorrected = true, senderTimestamp = 1_699_999_999u, reactionSummary = "\uD83D\uDC4D:3,\u2764\uFE0F:2",
            routeType = RouteType.TC_DIRECT, regionScope = "Germany")
        assertEquals(dto, decodeMessage(encodeMessage(dto)))
        assertEquals("00000000-0000-0000-0000-000000000004", encodeMessage(dto).getValue("id").toString().trim('"'))
    }

    @OriginalCase("BackupIntegrationTests::saveMessage persists send metadata and preview fields through export and import()")
    @Test fun liveRepositorySendMetadataFlowsThroughActualBackup() = runBlocking {
        val dto = message().copy(sendCount = 3, deduplicationKey = "save-message-fields", linkPreviewURL = "https://example.com",
            linkPreviewTitle = "Example", reactionSummary = "\uD83D\uDC4D:2")
        store.saveMessage(dto)
        val saved = requireNotNull(db.messages().byId(RADIO.value, dto.id)).toDTO()
        assertEquals(3L, saved.sendCount); assertEquals(dto.linkPreviewURL, saved.linkPreviewURL)
        assertEquals("Example", saved.linkPreviewTitle); assertEquals(dto.reactionSummary, saved.reactionSummary)
        val backup = AppBackupCodec().parseBackup(service.export(store).data)
        db.messages().clearRadio(RADIO.value)
        service.importBackup(backup, store)
        val actual = requireNotNull(db.messages().byId(RADIO.value, dto.id)).toDTO()
        assertEquals(3L, actual.sendCount); assertEquals(dto.linkPreviewURL, actual.linkPreviewURL)
        assertEquals("Example", actual.linkPreviewTitle); assertEquals(dto.reactionSummary, actual.reactionSummary)
    }

    private fun counters(): NodeStatusSnapshotDTO = snapshot().copy(
        sentDirect = 100u, sentFlood = 200u, receivedDirect = 300u, receivedFlood = 400u, directDuplicates = 11u, floodDuplicates = 22u)
}
