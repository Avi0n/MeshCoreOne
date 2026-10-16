package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupEnvelopeBuilderTest {
    @Test
    fun buildManifestMatchesArraySizes() {
        val envelope =
            BackupEnvelopeBuilder.build(
                BackupExportInput(
                    appVersion = "1.0.0",
                    appBuild = "42",
                    exportDate = 1_700_000_000.0,
                ),
            )
        assertTrue(envelope.manifest.validate(envelope))
        assertEquals(AppBackupEnvelope.CURRENT_VERSION, envelope.version)
        assertEquals("1.0.0", envelope.appVersion)
        assertEquals("42", envelope.appBuild)
    }

    @Test
    fun redactedForBackupStripsBluetoothMethods() {
        val device = operatorDevice()
        val redacted = device.redactedForBackup()
        assertEquals(1, redacted.connectionMethods.size)
        assertTrue(redacted.connectionMethods.first() is ConnectionMethod.WiFi)

        val envelope = envelopeWith(device)
        assertEquals(1, envelope.devices.first().connectionMethods.size)
        assertTrue(envelope.manifest.validate(envelope))
    }

    /**
     * A `.mc1backup` is a file the operator hands to someone else, so the export must carry no BLE
     * PIN, no coordinates and no RF configuration. Asserted on the encoded bytes as well as the
     * fields, because a leak is only a leak once it is written out.
     */
    @Test
    fun exportRedactsPinCoordinatesAndRadioConfiguration() {
        val device = operatorDevice()
        val exported = envelopeWith(device).devices.single()

        assertEquals(DeviceBackupDefaults.BLE_PIN, exported.blePin)
        assertEquals(DeviceBackupDefaults.LATITUDE, exported.latitude)
        assertEquals(DeviceBackupDefaults.LONGITUDE, exported.longitude)
        assertEquals(DeviceBackupDefaults.FREQUENCY, exported.frequency)
        assertEquals(DeviceBackupDefaults.BANDWIDTH, exported.bandwidth)
        assertEquals(DeviceBackupDefaults.SPREADING_FACTOR, exported.spreadingFactor)
        assertEquals(DeviceBackupDefaults.CODING_RATE, exported.codingRate)
        assertEquals(DeviceBackupDefaults.TX_POWER, exported.txPower)
        assertEquals(DeviceBackupDefaults.MAX_TX_POWER, exported.maxTxPower)
        assertEquals(DeviceBackupDefaults.CLIENT_REPEAT, exported.clientRepeat)
        assertEquals(DeviceBackupDefaults.PATH_HASH_MODE, exported.pathHashMode)
        assertEquals(DeviceBackupDefaults.MANUAL_ADD_CONTACTS, exported.manualAddContacts)
        assertEquals(DeviceBackupDefaults.AUTO_ADD_CONFIG, exported.autoAddConfig)
        assertEquals(DeviceBackupDefaults.AUTO_ADD_MAX_HOPS, exported.autoAddMaxHops)
        assertEquals(DeviceBackupDefaults.MULTI_ACKS, exported.multiAcks)
        assertEquals(DeviceBackupDefaults.TELEMETRY_MODE_BASE, exported.telemetryModeBase)
        assertEquals(DeviceBackupDefaults.TELEMETRY_MODE_LOC, exported.telemetryModeLoc)
        assertEquals(DeviceBackupDefaults.TELEMETRY_MODE_ENV, exported.telemetryModeEnv)
        assertEquals(DeviceBackupDefaults.ADVERT_LOCATION_POLICY, exported.advertLocationPolicy)
        assertNull(exported.preRepeatFrequency)
        assertNull(exported.preRepeatBandwidth)
        assertNull(exported.preRepeatSpreadingFactor)
        assertNull(exported.preRepeatCodingRate)
        assertNull(exported.appliedRadioPresetID)

        // The live-connection handle identifies the exporting phone's pairing, not the radio.
        assertNotEquals(device.id, exported.id)
        // publicKey and radioID are the reconciliation and partition keys; they must survive.
        assertEquals(device.radioID, exported.radioID)
        assertContentEqualsBytes(device.publicKey, exported.publicKey)

        val json = BackupEnvelopeBuilder.encode(BackupExportInput(APP_VERSION, APP_BUILD, EXPORT_DATE, listOf(device)))
        for (secret in SECRETS_THAT_MUST_NOT_TRAVEL) {
            assertFalse(json.contains(secret), "exported backup leaks $secret")
        }
    }

    @Test
    fun rebuildOfDecodedFullEnvelopeRoundTripsEncode() {
        val source = decodeBackupEnvelope(loadBackupFixture("full-envelope.json"))
        val rebuilt = BackupEnvelopeBuilder.build(exportInput(source))

        // Everything but the device family is passed through verbatim; devices go through the export
        // redaction, whose re-minted `id` is the one value a rebuild cannot reproduce.
        val expectedDevices =
            source.devices.mapIndexed { index, device ->
                device.redactedForBackup().copy(id = rebuilt.devices[index].id)
            }
        assertEquals(
            encodeBackupEnvelope(source.copy(devices = expectedDevices)),
            encodeBackupEnvelope(rebuilt),
        )
    }

    private fun envelopeWith(device: DeviceDto): AppBackupEnvelope =
        BackupEnvelopeBuilder.build(BackupExportInput(APP_VERSION, APP_BUILD, EXPORT_DATE, listOf(device)))

    private fun exportInput(source: AppBackupEnvelope): BackupExportInput = BackupExportInput(
        appVersion = source.appVersion,
        appBuild = source.appBuild,
        exportDate = source.exportDate,
        devices = source.devices,
        contacts = source.contacts,
        channels = source.channels,
        messages = source.messages,
        messageRepeats = source.messageRepeats,
        reactions = source.reactions,
        roomMessages = source.roomMessages,
        remoteNodeSessions = source.remoteNodeSessions,
        savedTracePaths = source.savedTracePaths,
        blockedChannelSenders = source.blockedChannelSenders,
        nodeStatusSnapshots = source.nodeStatusSnapshots,
        discoveredNodes = source.discoveredNodes,
        userDefaults = source.userDefaults,
        version = source.version,
    )

    /** A device configured the way a responder's radio actually is: PIN set, position known, RF tuned. */
    private fun operatorDevice(): DeviceDto = DeviceDto(
        id = Uuid.normalized("D0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(32) { 0x11 },
        nodeName = "Node",
        firmwareVersion = 1,
        firmwareVersionString = "v1",
        manufacturerName = "m",
        buildDate = "d",
        maxContacts = 10,
        maxChannels = 8,
        frequency = SECRET_FREQUENCY,
        bandwidth = SECRET_BANDWIDTH,
        spreadingFactor = 11,
        codingRate = 7,
        txPower = 27,
        maxTxPower = 30,
        latitude = SECRET_LATITUDE,
        longitude = SECRET_LONGITUDE,
        blePin = SECRET_BLE_PIN,
        clientRepeat = true,
        pathHashMode = 1,
        manualAddContacts = true,
        autoAddConfig = 3,
        autoAddMaxHops = 4,
        multiAcks = 1,
        telemetryModeBase = 3,
        telemetryModeLoc = 1,
        telemetryModeEnv = 1,
        advertLocationPolicy = 2,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
        connectionMethods = listOf(
            ConnectionMethod.WiFi("192.168.1.1", 5000, "Base"),
            ConnectionMethod.Bluetooth(Uuid.normalized("B0000000-0000-0000-0000-000000000001"), "BLE"),
        ),
        preRepeatFrequency = SECRET_PRE_REPEAT_FREQUENCY,
        preRepeatBandwidth = SECRET_BANDWIDTH,
        preRepeatSpreadingFactor = 12,
        preRepeatCodingRate = 8,
        appliedRadioPresetID = "br",
    )

    private fun assertContentEqualsBytes(expected: ByteArray, actual: ByteArray) {
        assertTrue(expected.contentEquals(actual), "publicKey must survive redaction")
    }

    private companion object {
        const val APP_VERSION = "1.0.0"
        const val APP_BUILD = "1"
        const val EXPORT_DATE = 1.0

        const val SECRET_BLE_PIN = 482_913L
        const val SECRET_LATITUDE = 37.7749
        const val SECRET_LONGITUDE = -122.4194
        const val SECRET_FREQUENCY = 869_525L
        const val SECRET_BANDWIDTH = 62_500L
        const val SECRET_PRE_REPEAT_FREQUENCY = 433_175L

        /** Verbatim as JSON numbers: what a reader of the exported file would grep for. */
        val SECRETS_THAT_MUST_NOT_TRAVEL = listOf(
            SECRET_BLE_PIN.toString(),
            SECRET_LATITUDE.toString(),
            SECRET_LONGITUDE.toString(),
            SECRET_FREQUENCY.toString(),
            SECRET_BANDWIDTH.toString(),
            SECRET_PRE_REPEAT_FREQUENCY.toString(),
        )
    }
}
