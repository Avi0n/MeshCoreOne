package one.meshcore.shared.backup

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.persistence.DiscoveredNodeEntity
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscoveredNodeBackupTest {
    private lateinit var dbFile: File
    private lateinit var targetFile: File

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("discover-src-", ".db")
        targetFile = File.createTempFile("discover-dst-", ".db")
        dbFile.deleteOnExit()
        targetFile.deleteOnExit()
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
        targetFile.delete()
    }

    @Test
    fun discoverListSurvivesExportDecodeImportWithRemint() = runBlocking {
        val source = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000D1")
        seedDevice(source, radio)
        val row = discoveredNode(radio, key = 0x11, name = "Repeater North", lastHeard = 100)
        source.discoveredNodeDao().upsert(row)

        val json = RoomBackupExporter.exportJson(source, "1.0.0", "1", exportDate = 1.0)
        source.close()

        val envelope = decodeBackupEnvelope(json)
        assertEquals(1, envelope.discoveredNodes.size)
        assertEquals(1, envelope.manifest.discoveredNodeCount)

        val target = meshDatabase(targetFile.absolutePath)
        val result = RoomBackupImporter.importEnvelope(target, envelope)
        assertEquals(1, result.discoveredNodesInserted)

        val imported = target.discoveredNodeDao().listByRadio(radio)
        assertEquals(1, imported.size)
        val restored = imported.single()
        assertEquals("Repeater North", restored.name)
        assertTrue(restored.publicKey.contentEquals(row.publicKey))
        assertEquals(100L, restored.lastHeardEpochSeconds)
        // The surrogate id is re-minted on import (no inbound FK depends on it).
        assertFalse(restored.id == row.id)
        target.close()
    }

    @Test
    fun reimportIsIdempotentOnPublicKey() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000D2")
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    discoveredNodes = listOf(discoveredDto(radio, key = 0x22, name = "Room A")),
                ),
            )
        assertEquals(1, RoomBackupImporter.importEnvelope(db, envelope).discoveredNodesInserted)
        // Second import of the same (radioID, publicKey) writes nothing new.
        assertEquals(0, RoomBackupImporter.importEnvelope(db, envelope).discoveredNodesInserted)
        assertEquals(1, db.discoveredNodeDao().listByRadio(radio).size)
        db.close()
    }

    @Test
    fun legacyEnvelopeWithoutDiscoverFamilyDecodesAndImports() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        // A pre-discover envelope: every family that shipped with the format is present, and only the
        // later-added discoveredNodes / discoveredNodeCount keys are absent. Omitting any of the rest
        // is a truncated file, not a legacy one, and must fail to decode.
        val legacyJson =
            """{"version":1,"appVersion":"0.9.0","appBuild":"1","exportDate":1.0,""" +
                """"devices":[],"contacts":[],"channels":[],"messages":[],"messageRepeats":[],""" +
                """"reactions":[],"roomMessages":[],"remoteNodeSessions":[],"savedTracePaths":[],""" +
                """"blockedChannelSenders":[],"nodeStatusSnapshots":[],""" +
                """"manifest":{"deviceCount":0,"contactCount":0,"channelCount":0,"messageCount":0,""" +
                """"messageRepeatCount":0,"reactionCount":0,"roomMessageCount":0,""" +
                """"remoteNodeSessionCount":0,"savedTracePathCount":0,"blockedChannelSenderCount":0,""" +
                """"nodeStatusSnapshotCount":0}}"""
        val envelope = decodeBackupEnvelope(legacyJson)
        envelope.validateForImport()
        assertTrue(envelope.discoveredNodes.isEmpty())
        val result = RoomBackupImporter.importEnvelope(db, envelope)
        assertEquals(0, result.discoveredNodesInserted)
        db.close()
    }

    @Test
    fun importSanitizesInvalidCoordinatesAndRejectsBadKey() = runBlocking {
        val db = meshDatabase(dbFile.absolutePath)
        val radio = Uuid.normalized("A0000000-0000-0000-0000-0000000000D3")
        val nullIsland = discoveredDto(radio, key = 0x33, name = "GhostFix").copy(latitude = 0.0, longitude = 0.0)
        val outOfRange = discoveredDto(radio, key = 0x44, name = "BadFix").copy(latitude = 999.0, longitude = 5.0)
        val shortKey =
            discoveredDto(radio, key = 0x55, name = "TooShort").copy(publicKey = ByteArray(8) { 0x55.toByte() })
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "1",
                    exportDate = 1.0,
                    discoveredNodes = listOf(nullIsland, outOfRange, shortKey),
                ),
            )
        val result = RoomBackupImporter.importEnvelope(db, envelope)
        // Two well-keyed rows import; the short-key row is rejected outright.
        assertEquals(2, result.discoveredNodesInserted)
        val rows = db.discoveredNodeDao().listByRadio(radio).associateBy { it.name }
        assertEquals(0.0, rows.getValue("BadFix").latitude)
        assertEquals(0.0, rows.getValue("BadFix").longitude)
        db.close()
    }

    private fun discoveredNode(radio: Uuid, key: Int, name: String, lastHeard: Long): DiscoveredNodeEntity =
        DiscoveredNodeEntity(
            id = Uuid.normalized("E0000000-0000-0000-0000-${key.toString().padStart(12, '0')}"),
            radioID = radio,
            publicKey = ByteArray(32) { key.toByte() },
            name = name,
            typeRawValue = 2,
            lastHeardEpochSeconds = lastHeard,
            lastAdvertTimestamp = lastHeard,
            latitude = 37.7749,
            longitude = -122.4194,
            outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
            outPath = ByteArray(0),
        )

    private fun discoveredDto(radio: Uuid, key: Int, name: String): DiscoveredNodeDto = DiscoveredNodeDto(
        id = Uuid.normalized("F0000000-0000-0000-0000-${key.toString().padStart(12, '0')}"),
        radioID = radio,
        publicKey = ByteArray(32) { key.toByte() },
        name = name,
        typeRawValue = 2,
        lastHeard = 50.0,
        lastAdvertTimestamp = 50,
        latitude = 37.0,
        longitude = -122.0,
        outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
        outPath = ByteArray(0),
    )

    private suspend fun seedDevice(db: MeshDatabase, radio: Uuid) {
        db.deviceDao().upsert(
            DeviceEntity(
                id = Uuid.normalized("D0000000-0000-0000-0000-${radio.value.takeLast(12)}"),
                radioID = radio,
                publicKey = ByteArray(32) { 0x11 },
                nodeName = "Node",
                firmwareVersion = 1,
                firmwareVersionString = "v1",
                manufacturerName = "m",
                buildDate = "d",
                maxContacts = 10,
                maxChannels = 8,
                frequency = 915_000,
                bandwidth = 250_000,
                spreadingFactor = 10,
                codingRate = 5,
                txPower = 20,
                maxTxPower = 20,
                latitude = 0.0,
                longitude = 0.0,
                blePin = 0,
                clientRepeat = false,
                pathHashMode = 0,
                manualAddContacts = true,
                autoAddConfig = 0,
                autoAddMaxHops = 0,
                multiAcks = 0,
                telemetryModeBase = 0,
                telemetryModeLoc = 0,
                telemetryModeEnv = 0,
                advertLocationPolicy = 0,
                lastConnected = 0.0,
                lastContactSync = 0,
                isActive = true,
            ),
        )
    }
}
