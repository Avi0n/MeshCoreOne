// PortedFrom: MC1Services/Tests/MC1ServicesTests/AppBackupEnvelopeTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppBackupEnvelopeTest : BackupRoomTest() {
    private val codec = AppBackupCodec()

    @OriginalCase("AppBackupEnvelopeTests::Envelope round-trips through JSON encode/decode()")
    @Test fun allFamiliesRoundTrip() {
        val expected = fullEnvelope()
        val actual = codec.parseBackup(codec.encode(expected))
        assertEquals(expected, actual)
        assertTrue(actual.manifest.validate(actual))
        assertEquals(12, BackupModelKind.entries.size)
        for (kind in BackupModelKind.entries) assertEquals(1L, actual.manifest.count(kind))
    }

    @OriginalCase("AppBackupEnvelopeTests::Backup JSON round-trips sub-second timestamps without truncation()")
    @Test fun unixFractionsRoundTrip() {
        val expected = envelope(
            devices = listOf(device()),
            sessions = listOf(session().copy(lastMessageDate = fraction("1700000501.1234567"))),
            snapshots = listOf(snapshot(date = fraction("1700000502.7654321"))),
            exportDate = fraction("1700000500.9876542"),
        )
        val actual = codec.parseBackup(codec.encode(expected))
        assertEquals(expected.exportDate, actual.exportDate)
        assertEquals(expected.remoteNodeSessions.single().lastMessageDate, actual.remoteNodeSessions.single().lastMessageDate)
        assertEquals(expected.nodeStatusSnapshots.single().timestamp, actual.nodeStatusSnapshots.single().timestamp)
        val negative = expected.copy(exportDate = fraction("-0.000000001"))
        assertEquals(-1L, negative.exportDate.epochSecond)
        assertEquals(999999999, negative.exportDate.nano)
        assertEquals(negative, codec.parseBackup(codec.encode(negative)))
    }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup decompresses and decodes a valid backup()")
    @Test fun parseActualCompressedBackup() {
        val actual = codec.parseBackup(codec.encode(fullEnvelope()))
        assertEquals(1L, actual.version)
        assertEquals("1.0.0", actual.appVersion)
        assertEquals(1, actual.devices.size)
        assertEquals(1, actual.contacts.size)
    }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup throws invalidFile for garbage data()")
    @Test fun rejectGarbage() { assertEquals(AppBackupError.InvalidFile, failure(Bytes.of(0, 255, 171, 205)).error) }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup throws invalidFile for truncated zlib payloads()")
    @Test fun rejectTruncatedStream() {
        val bytes = codec.encode(fullEnvelope())
        assertEquals(AppBackupError.InvalidFile, failure(bytes.prefix(bytes.size / 2)).error)
        assertEquals(AppBackupError.InvalidFile, failure(bytes.prefix(bytes.size - 1)).error)
    }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup rejects files larger than the size cap()")
    @Test fun compressedExactCap() {
        BackupContract.validateCompressedSize(52_428_800)
        val failure = assertThrows(AppBackupException::class.java) { BackupContract.validateCompressedSize(52_428_801) }
        assertEquals(AppBackupError.FileTooLarge(52_428_801, 52_428_800), failure.error)
    }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup rejects files larger than the size cap()")
    @Test fun oversizedBodyIsRejectedByParserBeforeDecompression() {
        val oversized = Bytes(ByteArray(52_428_801))
        val failure = assertThrows(AppBackupException::class.java) {
            // Invalid DEFLATE and a zero expanded cap must not mask the compressed-size error.
            codec.parseBackup(oversized, maxUncompressedBytes = 0)
        }
        assertEquals(AppBackupError.FileTooLarge(52_428_801, 52_428_800), failure.error)
    }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup rejects payloads whose decompressed size exceeds the cap()")
    @Test fun expandedBombBound() {
        val compressed = Bytes(ByteArray(2 * 1_048_576)).zlibCompressed()
        assertTrue(compressed.size < BackupContract.MAX_COMPRESSED_BYTES)
        assertEquals(AppBackupError.DecompressedTooLarge(1_048_576),
            assertThrows(AppBackupException::class.java) { codec.parseBackup(compressed, 1_048_576) }.error)
    }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup throws unsupportedVersion for future versions()")
    @Test fun futureVersionIsSpecific() {
        assertEquals(AppBackupError.UnsupportedVersion(999, 1),
            failure(envelope().jsonObject().replacing("version", JsonPrimitive(999)).compressed()).error)
        for (version in listOf(0L, -1L, 1L)) {
            assertEquals(version, codec.parseBackup(envelope().jsonObject().replacing("version", JsonPrimitive(version)).compressed()).version)
        }
    }

    @OriginalCase("AppBackupEnvelopeTests::parseBackup throws corruptedManifest when counts mismatch()")
    @Test fun countMismatchIsSpecific() {
        val expected = fullEnvelope()
        assertEquals(AppBackupError.CorruptedManifest, failure(codec.encode(expected.copy(manifest = BackupManifest()))).error)
        for (kind in BackupModelKind.entries) {
            val objectValue = expected.jsonObject()
            val manifest = objectValue.getValue("manifest").jsonObject.replacing(kind.countKey, JsonPrimitive(99))
            assertEquals(AppBackupError.CorruptedManifest, failure(objectValue.replacing("manifest", manifest).compressed()).error)
        }
    }

    @OriginalCase("AppBackupEnvelopeTests::DiscoveredNodeDTO survives JSON encode → decode round-trip()")
    @Test fun discoveredAllFieldsRoundTrip() {
        val dto = discovered().copy(publicKey = key(), name = "Repeater-7", typeRawValue = 2u, lastAdvertTimestamp = 42u,
            latitude = 37.3349, longitude = -122.009, outPathLength = 3u, outPath = Bytes.of(1, 2, 3),
            inboundHopCount = 2, inboundHopAdvertTimestamp = 99u)
        assertEquals(dto, decodeDiscoveredNode(encodeDiscoveredNode(dto)))
    }

    @OriginalCase("AppBackupEnvelopeTests::Legacy envelope without discoveredNodes decodes to empty array()")
    @Test fun onlyDiscoveredFamilyIsLegacyOptional() {
        val value = envelope().jsonObject()
        val legacy = value.replacing("discoveredNodes", null).replacing("manifest",
            value.getValue("manifest").jsonObject.replacing("discoveredNodeCount", null))
        val actual = codec.parseBackup(legacy.compressed())
        assertTrue(actual.discoveredNodes.isEmpty())
        assertEquals(0L, actual.manifest.discoveredNodeCount)
        assertTrue(actual.manifest.validate(actual))
        for (kind in BackupModelKind.entries.filter { it != BackupModelKind.DISCOVERED_NODES }) {
            assertEquals(AppBackupError.InvalidFile, failure(value.replacing(kind.arrayKey, null).compressed()).error)
        }
    }

    @OriginalCase("AppBackupEnvelopeTests::Manifest validates correctly when counts match()")
    @Test fun manifestMatchesAllActualFamilies() { assertTrue(fullEnvelope().manifest.validate(fullEnvelope())) }

    @OriginalCase("AppBackupEnvelopeTests::Manifest detects mismatch()")
    @Test fun manifestDoesNotMatchAddedRow() {
        val expected = fullEnvelope()
        assertFalse(expected.manifest.validate(expected.copy(devices = SnapshotList.of(device(), device(OTHER_RADIO)))))
    }

    @OriginalCase("AppBackupEnvelopeTests::ImportResult computes totals correctly()")
    @Test fun countsAndChangeMeaning() {
        val accounting = ImportAccounting()
        accounting.record(BackupModelKind.DEVICES, inserted = 2)
        accounting.record(BackupModelKind.CONTACTS, inserted = 5, merged = 1)
        accounting.record(BackupModelKind.MESSAGES, skipped = 3)
        val result = accounting.result().copy(userDefaultsRestored = true)
        assertEquals(7L, result.totalInserted)
        assertEquals(1L, result.totalMerged)
        assertEquals(3L, result.totalSkipped)
        assertEquals(8L, result.totalRestoredRecordCount)
        assertTrue(result.hasRestoredChanges)
        assertFalse(ImportResult().hasRestoredChanges)
    }

    @OriginalCase("AppBackupEnvelopeTests::BackupUserDefaults round-trips through JSON()")
    @Test fun allPreferenceWireValuesRoundTrip() {
        val expected = BackupUserDefaults(hasCompletedOnboarding = true, mapStyleSelection = "topo",
            autoDeleteStaleNodesDays = 30, frequentEmojis = SnapshotList.of("\uD83D\uDC4D", "\u2764\uFE0F"),
            recentEmojis = SnapshotList.of("\uD83D\uDE02", "\uD83D\uDE2E"), notifyContactMessages = false,
            linkPreviewsEnabled = true, showIncomingRegion = true, showIncomingHeardCount = true,
            showIncomingSendTime = true, showInlineImages = false)
        assertEquals(expected, BackupUserDefaults.decode(expected.encode()))
        assertTrue(expected.encode().getValue("frequentEmojis") is JsonArray)
    }

    @OriginalCase("AppBackupEnvelopeTests::Legacy envelope without showIncomingSendTime decodes to nil and restore skips it()")
    @Test fun legacySendTimeMissingIsNotDefaulted() = runBlocking {
        val actual = BackupUserDefaults.decode(backupJson.parseToJsonElement("{\"hasCompletedOnboarding\":true}"))
        assertNull(actual.showIncomingSendTime)
        assertFalse(actual.restore(storage.backupPreferences).contains("showIncomingSendTime"))
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.showIncomingSendTime))
    }

    @OriginalCase("AppBackupEnvelopeTests::Legacy envelope without showIncomingHeardCount decodes to nil and restore skips it()")
    @Test fun legacyHeardCountMissingIsNotDefaulted() = runBlocking {
        val actual = BackupUserDefaults.decode(backupJson.parseToJsonElement("{\"hasCompletedOnboarding\":true}"))
        assertNull(actual.showIncomingHeardCount)
        assertFalse(actual.restore(storage.backupPreferences).contains("showIncomingHeardCount"))
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.showIncomingHeardCount))
    }

    @OriginalCase("AppBackupEnvelopeTests::Legacy envelope without map preference keys decodes to nil and restore skips them()")
    @Test fun legacyMapPresencePreservesLocal() = runBlocking {
        val actual = BackupUserDefaults.decode(backupJson.parseToJsonElement("{\"hasCompletedOnboarding\":true}"))
        assertNull(actual.showDiscoveredNodesOnMap); assertNull(actual.mapColorSchemePreference); assertNull(actual.mapClusteringEnabled)
        storage.preferences.set(AppStorageKey.showDiscoveredNodesOnMap, false)
        storage.preferences.set(AppStorageKey.mapColorSchemePreference, "light")
        storage.preferences.set(AppStorageKey.mapClusteringEnabled, false)
        val keys = actual.restore(storage.backupPreferences)
        assertFalse(keys.contains("showDiscoveredNodesOnMap")); assertFalse(keys.contains("mapColorSchemePreference"))
        assertFalse(keys.contains("mapClusteringEnabled"))
        assertFalse(storage.preferences.get(AppStorageKey.showDiscoveredNodesOnMap))
        assertEquals("light", storage.preferences.get(AppStorageKey.mapColorSchemePreference))
        assertFalse(storage.preferences.get(AppStorageKey.mapClusteringEnabled))
    }

    @OriginalCase("AppBackupEnvelopeTests::Map preference keys round-trip non-default values through restore()")
    @Test fun nonDefaultMapRestore() = runBlocking {
        val actual = BackupUserDefaults.decode(BackupUserDefaults(showDiscoveredNodesOnMap = true,
            mapColorSchemePreference = "dark", mapClusteringEnabled = false).encode())
        val keys = actual.restore(storage.backupPreferences)
        assertTrue(keys.containsAll(listOf("showDiscoveredNodesOnMap", "mapColorSchemePreference", "mapClusteringEnabled")))
        assertTrue(storage.preferences.get(AppStorageKey.showDiscoveredNodesOnMap))
        assertEquals("dark", storage.preferences.get(AppStorageKey.mapColorSchemePreference))
        assertFalse(storage.preferences.get(AppStorageKey.mapClusteringEnabled))
    }

    @OriginalCase("AppBackupEnvelopeTests::Legacy envelope without mapFilter keys decodes to nil and restore skips them()")
    @Test fun missingFiltersDoNotOverrideLocal() = runBlocking {
        val actual = BackupUserDefaults.decode(backupJson.parseToJsonElement("{\"hasCompletedOnboarding\":true}"))
        assertNull(actual.mapFilterMainMap); assertNull(actual.mapFilterTracePath); assertNull(actual.mapFilterNeighborSNR)
        storage.preferences.set(AppStorageKey.mapFilterMainMap, "{\"favoritesOnly\":false}")
        assertFalse(actual.restore(storage.backupPreferences).contains("mapFilterMainMap"))
        assertEquals("{\"favoritesOnly\":false}", storage.preferences.get(AppStorageKey.mapFilterMainMap))
    }

    @OriginalCase("AppBackupEnvelopeTests::mapFilterMainMap non-default JSON string round-trips through restore()")
    @Test fun allFilterStringsAreOpaque() = runBlocking {
        val main = "{\"favoritesOnly\":true,\"showDiscovered\":false,\"showChat\":true,\"showRepeater\":false,\"showRoom\":true}"
        val trace = "{\"favoritesOnly\":false,\"showDiscovered\":true,\"showChat\":true,\"showRepeater\":true,\"showRoom\":true}"
        val neighbor = "{\"favoritesOnly\":true,\"showDiscovered\":false,\"showChat\":true,\"showRepeater\":true,\"showRoom\":true}"
        val expected = BackupUserDefaults(mapFilterMainMap = main, mapFilterTracePath = trace, mapFilterNeighborSNR = neighbor)
        val actual = BackupUserDefaults.decode(expected.encode())
        assertEquals(expected, actual)
        assertTrue(actual.restore(storage.backupPreferences).containsAll(listOf("mapFilterMainMap", "mapFilterTracePath", "mapFilterNeighborSNR")))
        assertEquals(main, storage.preferences.get(AppStorageKey.mapFilterMainMap))
        assertEquals(trace, storage.preferences.get(AppStorageKey.mapFilterTracePath))
        assertEquals(neighbor, storage.preferences.get(AppStorageKey.mapFilterNeighborSNR))
    }

    @OriginalCase("AppBackupEnvelopeTests::dual legacy discovered and mapFilterMainMap both preserve on envelope wire()")
    @Test fun independentDiscoveredAndFilterValues() {
        val expected = BackupUserDefaults(showDiscoveredNodesOnMap = true, mapFilterMainMap =
            "{\"favoritesOnly\":false,\"showDiscovered\":false,\"showChat\":true,\"showRepeater\":true,\"showRoom\":true}")
        assertEquals(expected, BackupUserDefaults.decode(expected.encode()))
        assertTrue(requireNotNull(expected.mapFilterMainMap).contains("\"showDiscovered\":false"))
    }

    @OriginalCase("AppBackupEnvelopeTests::showInlineImages survives restore and never reconstructs the link-content toggle()")
    @Test fun inlineImagePresenceNeverInventsLinkToggle() = runBlocking {
        val actual = BackupUserDefaults.decode(BackupUserDefaults(showInlineImages = false).encode())
        assertEquals(false, actual.showInlineImages); assertNull(actual.linkPreviewsEnabled)
        val keys = actual.restore(storage.backupPreferences)
        assertTrue(keys.contains("showInlineImages")); assertFalse(keys.contains("linkPreviewsEnabled"))
        assertFalse(storage.preferences.get(AppStorageKey.showInlineImages))
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.linkPreviewsEnabled))
    }

    @OriginalCase("AppBackupEnvelopeTests::Legacy envelope without showInlineImages decodes to nil and restore skips it()")
    @Test fun legacyImagesDoNotCreateEitherToggle() = runBlocking {
        val actual = BackupUserDefaults.decode(backupJson.parseToJsonElement("{\"hasCompletedOnboarding\":true}"))
        assertNull(actual.showInlineImages)
        val keys = actual.restore(storage.backupPreferences)
        assertFalse(keys.contains("showInlineImages")); assertFalse(keys.contains("linkPreviewsEnabled"))
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.showInlineImages))
        assertFalse(storage.preferences.snapshot().contains(AppStorageKey.linkPreviewsEnabled))
    }

    @OriginalCase("AppBackupEnvelopeTests::AppBackupError provides user-facing descriptions()")
    @Test fun descriptionsNeverPromiseRollback() {
        for (error in listOf(AppBackupError.InvalidFile, AppBackupError.FileTooLarge(100000000, 50000000),
            AppBackupError.DecompressedTooLarge(536870912), AppBackupError.UnsupportedVersion(5, 1),
            AppBackupError.CorruptedManifest, AppBackupError.ExportFailed(Exception("secret")),
            AppBackupError.ImportFailed(Exception("secret")))) {
            assertTrue(error.description().isNotEmpty())
            assertFalse(error.description().contains("secret"))
        }
        assertFalse(AppBackupError.ImportFailed(Exception()).description().lowercase().contains("no data was changed"))
        assertEquals(RestoreOutcome.CANCELLED, RestoreOutcome.valueOf("CANCELLED"))
    }

    private fun failure(bytes: Bytes): AppBackupException =
        assertThrows(AppBackupException::class.java) { codec.parseBackup(bytes) }
}
