package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.toDto
import one.meshcore.shared.persistence.toEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DeviceAppliedRadioPresetDtoTest {
    @Test
    fun appliedRadioPresetIDRoundTrips() {
        val encoded = backupJson.encodeToString(DeviceDto.serializer(), fixture("br"))
        val decoded = backupJson.decodeFromString(DeviceDto.serializer(), encoded)
        assertEquals("br", decoded.appliedRadioPresetID)
    }

    @Test
    fun aLegacyEnvelopeWithoutTheKeyDecodesAsNull() {
        val encoded = backupJson.encodeToString(DeviceDto.serializer(), fixture(null))
        assertFalse("appliedRadioPresetID" in encoded)
        val decoded = backupJson.decodeFromString(DeviceDto.serializer(), encoded)
        assertNull(decoded.appliedRadioPresetID)
    }

    @Test
    fun redactedForBackupNilsAppliedRadioPresetID() {
        assertNull(fixture("br").redactedForBackup().appliedRadioPresetID)
    }

    @Test
    fun mapperForwardsTheCatalogId() {
        val dto = fixture("br")
        assertEquals("br", dto.toEntity().appliedRadioPresetID)
        assertEquals("br", dto.toEntity().toDto().appliedRadioPresetID)
    }

    private fun fixture(appliedRadioPresetID: String?) = DeviceDto(
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
        frequency = 869_525,
        bandwidth = 62_500,
        spreadingFactor = 11,
        codingRate = 7,
        txPower = 27,
        maxTxPower = 30,
        latitude = 0.0,
        longitude = 0.0,
        blePin = 0,
        clientRepeat = false,
        pathHashMode = 0,
        manualAddContacts = false,
        autoAddConfig = 0,
        autoAddMaxHops = 0,
        multiAcks = 2,
        telemetryModeBase = 2,
        telemetryModeLoc = 0,
        telemetryModeEnv = 0,
        advertLocationPolicy = 0,
        lastConnected = 0.0,
        lastContactSync = 0,
        isActive = true,
        appliedRadioPresetID = appliedRadioPresetID,
    )
}
