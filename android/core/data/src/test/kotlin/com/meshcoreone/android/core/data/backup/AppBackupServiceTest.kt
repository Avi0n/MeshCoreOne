// PortedFrom: MC1Services/Tests/MC1ServicesTests/AppBackupServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppBackupServiceTest : BackupRoomTest() {
    private val codec = AppBackupCodec()

    @OriginalCase("AppBackupServiceTests::Export produces valid compressed backup()")
    @Test fun realExportContainsSeededRecords() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(message(), message(id = id(40)))))
        store.updateMessageLinkPreview(EntityKey(RADIO, id(40)),
            "https://example.com", "Example", Bytes(ByteArray(1024) { 0xFF.toByte() }), Bytes(ByteArray(32) { 0xFE.toByte() }), true)
        val result = service.export(store)
        assertFalse(result.data.isEmpty)
        val actual = codec.parseBackup(result.data)
        assertEquals(1, actual.devices.size); assertEquals(1, actual.contacts.size); assertEquals(2, actual.messages.size)
        assertTrue(actual.manifest.validate(actual))
    }

    @OriginalCase("AppBackupServiceTests::Export strips link preview blobs from messages()")
    @Test fun previewBlobsAreExcludedAtQueryBoundary() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(message())))
        val row = requireNotNull(db.messages().byId(RADIO.value, id(4)))
        db.messages().upsert(row.copy(linkPreviewURL = "https://example.com", linkPreviewTitle = "Example Domain",
            linkPreviewImageData = Bytes(ByteArray(500) { 1 }), linkPreviewIconData = Bytes(ByteArray(64) { 2 }), linkPreviewFetched = true))
        val exported = codec.parseBackup(service.export(store).data).messages.single()
        assertEquals("https://example.com", exported.linkPreviewURL)
        assertEquals("Example Domain", exported.linkPreviewTitle)
        assertNull(exported.linkPreviewImageData); assertNull(exported.linkPreviewIconData); assertFalse(exported.linkPreviewFetched)
        assertTrue(requireNotNull(db.messages().byId(RADIO.value, id(4))).linkPreviewFetched)
        assertEquals(500, requireNotNull(db.messages().byId(RADIO.value, id(4))).linkPreviewImageData?.size)
    }

    @OriginalCase("AppBackupServiceTests::Export succeeds with empty store()")
    @Test fun emptyHistoryExportIsReal() = runBlocking {
        seed(envelope(devices = listOf(device())))
        val actual = codec.parseBackup(service.export(store).data)
        assertEquals(1, actual.devices.size)
        assertTrue(actual.contacts.isEmpty()); assertTrue(actual.messages.isEmpty())
        assertTrue(actual.manifest.validate(actual))
    }

    @OriginalCase("AppBackupServiceTests::Manifest counts match exported array sizes()")
    @Test fun manifestAccuratelyCountsRealRows() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = (1..3).map { contact(publicKey = key(it), id = id(20L + it)) }))
        val actual = codec.parseBackup(service.export(store).data)
        assertEquals(3L, actual.manifest.contactCount)
        assertEquals(1L, actual.manifest.deviceCount)
        assertTrue(actual.manifest.validate(actual))
    }

    @OriginalCase("AppBackupServiceTests::Export output is smaller than raw JSON for large payloads()")
    @Test fun compressionHasActualRatio() = runBlocking {
        val sourceKeys = (0 until 20).map { value ->
            Bytes(ByteArray(32) { (value % 255 + 1).toByte() } + byteArrayOf((value / 255).toByte()))
        }
        assertCompressionPreservesKeys(sourceKeys, 33)
    }

    // AndroidOnly: WP-203 Canonical native input is supplemental, not the source's 33-byte fixture.
    @Test fun canonical32ByteContactKeysAlsoRetainCompressionPredicate() = runBlocking {
        assertCompressionPreservesKeys((1..20).map(::key), 32)
    }

    private suspend fun assertCompressionPreservesKeys(expectedKeys: List<Bytes>, expectedWidth: Int) {
        seed(envelope(devices = listOf(device()), contacts = expectedKeys.mapIndexed { index, publicKey ->
            contact(publicKey = publicKey, id = id(21L + index))
        }))
        val result = service.export(store)
        val inflater = Inflater(true)
        val expanded = try {
            InflaterInputStream(ByteArrayInputStream(result.data.toByteArray()), inflater).use { input ->
                val bytes = input.readNBytes(64 * 1024)
                assertEquals("The twenty-contact fixture must fit the bounded independent decoder", -1, input.read())
                assertTrue(inflater.finished())
                bytes
            }
        } finally {
            inflater.end()
        }
        assertTrue(expanded.size > result.data.size)
        val jsonKeys = backupJson.parseToJsonElement(expanded.toString(Charsets.UTF_8)).jsonObject
            .getValue("contacts").jsonArray.map { it.jsonObject.getValue("publicKey").jsonPrimitive.content }
        assertEquals(20, jsonKeys.size)
        assertEquals(expectedKeys.map { Base64.getEncoder().encodeToString(it.toByteArray()) }.toSet(), jsonKeys.toSet())
        assertTrue(jsonKeys.all { Base64.getDecoder().decode(it).size == expectedWidth })
        val decoded = codec.parseBackup(result.data)
        assertEquals(20, decoded.contacts.size)
        assertEquals(expectedKeys.toSet(), decoded.contacts.map { it.publicKey }.toSet())
        assertTrue(decoded.contacts.all { it.publicKey.size == expectedWidth })
        assertEquals(expectedKeys.toSet(), db.contacts().forRadio(RADIO.value).map { it.publicKey }.toSet())
    }

    @OriginalCase("AppBackupServiceTests::Envelope contains expected version()")
    @Test fun currentVersionExported() = runBlocking {
        seed(envelope(devices = listOf(device())))
        assertEquals(BackupContract.CURRENT_VERSION, codec.parseBackup(service.export(store).data).version)
    }

    @OriginalCase("AppBackupServiceTests::Import into empty store restores all records()")
    @Test fun everyTableActuallyRestored() = runBlocking {
        val value = fullEnvelope()
        val result = service.importBackup(value, store)
        for (kind in BackupModelKind.entries) assertEquals(1L, result.count(kind).inserted)
        assertEquals(0L, result.totalSkipped)
        assertEquals(1, db.devices().all().size); assertEquals(1, db.contacts().forRadio(RADIO.value).size)
        val exported = service.exportEnvelope(store)
        for (kind in BackupModelKind.entries) assertEquals(1L, exported.manifest.count(kind))
        assertEquals(value.messages.single().id, exported.messages.single().id)
        assertEquals(value.remoteNodeSessions.single().id, exported.roomMessages.single().sessionID)
    }

    @OriginalCase("AppBackupServiceTests::Import remaps radioID when local device has same publicKey()")
    @Test fun matchUsesFullPublicKeyAndLocalPartition() = runBlocking {
        seed(envelope(devices = listOf(device(OTHER_RADIO))))
        val value = envelope(devices = listOf(device()), contacts = listOf(contact()), channels = listOf(channel(index = 1u)),
            messages = listOf(message()))
        val result = service.importBackup(value, store)
        assertEquals(0L, result.count(BackupModelKind.DEVICES).inserted)
        assertEquals(1L, result.count(BackupModelKind.CONTACTS).inserted)
        assertEquals(1L, result.count(BackupModelKind.CHANNELS).inserted)
        assertEquals(1L, result.count(BackupModelKind.MESSAGES).inserted)
        assertEquals(1, db.contacts().forRadio(OTHER_RADIO.value).size)
        assertTrue(db.contacts().forRadio(RADIO.value).isEmpty())
        assertEquals("General", db.channels().forRadio(OTHER_RADIO.value).single().name)
        assertEquals(OTHER_RADIO.value, db.messages().byId(OTHER_RADIO.value, id(4))?.radioId)
    }

    @OriginalCase("AppBackupServiceTests::Export redacts sensitive device fields (BLE PIN, radio config)()")
    @Test fun sourceSensitiveRedactionIsComplete() = runBlocking {
        val sensitive = device().copy(blePin = 123456u, frequency = 869500u, bandwidth = 125000u,
            spreadingFactor = 12u, codingRate = 8u, txPower = 14, maxTxPower = 22, latitude = 48.8566, longitude = 2.3522,
            clientRepeat = true, pathHashMode = 2u, preRepeatFrequency = 915000u, preRepeatBandwidth = 250000u,
            preRepeatSpreadingFactor = 10u, preRepeatCodingRate = 5u, autoAddConfig = 15u, autoAddMaxHops = 3u,
            telemetryModeBase = 3u, telemetryModeLoc = 2u, telemetryModeEnv = 1u, advertLocationPolicy = 2u,
            manualAddContacts = true, multiAcks = 4u, appliedRadioPresetID = "br",
            connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(id(99), "BT"), ConnectionMethod.WiFi("10.0.0.2", 5000u, "WiFi")))
        seed(envelope(devices = listOf(sensitive)))
        val exported = codec.parseBackup(service.export(store).data).devices.single()
        assertNotEquals(sensitive.id, exported.id)
        val expected = sensitive.copy(id = exported.id, blePin = 0u, frequency = 915000u, bandwidth = 250000u,
            spreadingFactor = 10u, codingRate = 5u, txPower = 20, maxTxPower = 20, latitude = 0.0, longitude = 0.0,
            clientRepeat = false, pathHashMode = 0u, preRepeatFrequency = null, preRepeatBandwidth = null,
            preRepeatSpreadingFactor = null, preRepeatCodingRate = null, autoAddConfig = 0u, autoAddMaxHops = 0u,
            telemetryModeBase = 2u, telemetryModeLoc = 0u, telemetryModeEnv = 0u, advertLocationPolicy = 0u,
            manualAddContacts = false, multiAcks = 2u, appliedRadioPresetID = null,
            connectionMethods = SnapshotList.of(ConnectionMethod.WiFi("10.0.0.2", 5000u, "WiFi")))
        assertEquals(expected, exported)
        assertEquals(0u, exported.blePin); assertEquals(915000u, exported.frequency); assertEquals(250000u, exported.bandwidth)
        assertEquals(10.toUByte(), exported.spreadingFactor); assertEquals(5.toUByte(), exported.codingRate)
        assertEquals(20.toByte(), exported.txPower); assertEquals(20.toByte(), exported.maxTxPower)
        assertEquals(0.0, exported.latitude, 0.0); assertEquals(0.0, exported.longitude, 0.0)
        assertFalse(exported.clientRepeat); assertEquals(0.toUByte(), exported.pathHashMode)
        assertNull(exported.preRepeatFrequency); assertNull(exported.preRepeatBandwidth)
        assertNull(exported.preRepeatSpreadingFactor); assertNull(exported.preRepeatCodingRate)
        assertEquals(0.toUByte(), exported.autoAddConfig); assertEquals(0.toUByte(), exported.autoAddMaxHops)
        assertEquals(2.toUByte(), exported.telemetryModeBase); assertEquals(0.toUByte(), exported.telemetryModeLoc)
        assertEquals(0.toUByte(), exported.telemetryModeEnv); assertEquals(0.toUByte(), exported.advertLocationPolicy)
        assertFalse(exported.manualAddContacts); assertEquals(2.toUByte(), exported.multiAcks); assertNull(exported.appliedRadioPresetID)
        assertEquals(1, exported.connectionMethods.size); assertTrue(exported.connectionMethods.single().isWiFi)
        assertEquals(sensitive, db.devices().all().single().toDTO())
    }

    @OriginalCase("AppBackupServiceTests::Export preserves non-sensitive device fields()")
    @Test fun nonSensitiveDeviceMetadataSurvives() = runBlocking {
        val expected = device(publicKey = key(0xF7)).copy(nodeName = "FieldUnit", firmwareVersion = 11u,
            firmwareVersionString = "v1.15.0", lastConnected = AT, lastContactSync = 42u,
            ocvPreset = "liIon", customOCVArrayString = "4200,4100,4000", knownRegions = SnapshotList.of("US", "EU"),
            connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(id(90)), ConnectionMethod.WiFi("10.0.0.2", 5000u, "WiFi")))
        seed(envelope(devices = listOf(expected)))
        val exported = codec.parseBackup(service.export(store).data).devices.single()
        assertEquals(expected.publicKey, exported.publicKey); assertEquals("FieldUnit", exported.nodeName)
        assertEquals("v1.15.0", exported.firmwareVersionString); assertEquals(AT, exported.lastConnected)
        assertEquals(42u, exported.lastContactSync); assertEquals("liIon", exported.ocvPreset)
        assertEquals("4200,4100,4000", exported.customOCVArrayString)
        assertEquals(listOf("US", "EU"), exported.knownRegions)
        assertEquals(1, exported.connectionMethods.size); assertTrue(exported.connectionMethods.single().isWiFi)
    }

    @OriginalCase("AppBackupServiceTests::Export strips Bluetooth connection methods()")
    @Test fun bluetoothHandlesAreNeverExported() = runBlocking {
        seed(envelope(devices = listOf(device().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(id(90)))))))
        assertTrue(codec.parseBackup(service.export(store).data).devices.single().connectionMethods.isEmpty())
    }

    @OriginalCase("AppBackupServiceTests::Import strips Bluetooth connection methods from legacy backups()")
    @Test fun legacyBluetoothHandleIsNeverRestoredAsPairing() = runBlocking {
        val legacy = device().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(id(90), "BT"),
            ConnectionMethod.WiFi("10.0.0.2", 5000u, "WiFi")), isActive = true)
        service.importBackup(envelope(devices = listOf(legacy)), store)
        val restored = db.devices().all().single().toDTO()
        assertEquals(1, restored.connectionMethods.size); assertTrue(restored.connectionMethods.single().isWiFi)
        assertFalse(restored.isActive)
    }

    @OriginalCase("AppBackupServiceTests::Redacted device round-trips through export and import with safe defaults()")
    @Test fun redactedDeviceRoundTripHasSafeDefaults() = runBlocking {
        val sensitive = device(publicKey = key(0xF8)).copy(blePin = 999999u, frequency = 433000u, txPower = 10)
        seed(envelope(devices = listOf(sensitive)))
        val actual = codec.parseBackup(service.export(store).data)
        withIndependentStore { destination, destinationStore ->
            assertNotSame(db, destination)
            assertTrue(destination.devices().all().isEmpty())
            val result = service.importBackup(actual, destinationStore)
            assertEquals(1L, result.count(BackupModelKind.DEVICES).inserted)
            val restored = destination.devices().all().single().toDTO()
            assertEquals(0u, restored.blePin); assertEquals(915000u, restored.frequency); assertEquals(20.toByte(), restored.txPower)
            assertEquals(key(0xF8), restored.publicKey)
            assertNotEquals(sensitive.id, restored.id)
            assertEquals(sensitive, db.devices().all().single().toDTO())
        }
    }

    @OriginalCase("AppBackupServiceTests::Importing same backup twice results in zero inserts on second pass()")
    @Test fun reimportIsIdempotent() = runBlocking {
        val value = envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(message().copy(deduplicationKey = "test-dedup-key-1")))
        val first = service.importBackup(value, store)
        assertEquals(1L, first.count(BackupModelKind.DEVICES).inserted)
        assertEquals(1L, first.count(BackupModelKind.CONTACTS).inserted); assertEquals(1L, first.count(BackupModelKind.MESSAGES).inserted)
        val second = service.importBackup(value, store)
        assertEquals(0L, second.totalInserted)
        assertEquals(0L, second.count(BackupModelKind.DEVICES).inserted)
        assertEquals(0L, second.count(BackupModelKind.CONTACTS).inserted); assertEquals(0L, second.count(BackupModelKind.MESSAGES).inserted)
    }

    @OriginalCase("AppBackupServiceTests::Export returns ExportResult carrying the envelope manifest()")
    @Test fun exportManifestIsActualSnapshot() = runBlocking {
        seed(envelope(devices = listOf(device()), contacts = listOf(contact()), messages = listOf(message())))
        val result = service.export(store)
        assertFalse(result.data.isEmpty)
        assertEquals(1L, result.manifest.contactCount); assertEquals(1L, result.manifest.messageCount)
        assertEquals(1L, result.manifest.count(BackupModelKind.CONTACTS)); assertEquals(1L, result.manifest.count(BackupModelKind.MESSAGES))
        assertEquals(result.manifest, codec.parseBackup(result.data).manifest)
    }

    @OriginalCase("AppBackupServiceTests::Import assigns fresh Device.id so an id collision cannot upsert the local row()")
    @Test fun collidingDeviceSurrogateCannotOverwriteLocalRadio() = runBlocking {
        seed(envelope(devices = listOf(device(publicKey = key(0xB0)))))
        val result = service.importBackup(envelope(devices = listOf(device(OTHER_RADIO, key(0xA0)))), store)
        assertEquals(1L, result.count(BackupModelKind.DEVICES).inserted)
        val all = db.devices().all().map { it.toDTO() }
        assertEquals(2, all.size)
        assertEquals(id(1), all.single { it.publicKey == key(0xB0) }.id)
        assertNotEquals(id(1), all.single { it.publicKey == key(0xA0) }.id)
        assertEquals(OTHER_RADIO, all.single { it.publicKey == key(0xA0) }.radioId)
    }
}
