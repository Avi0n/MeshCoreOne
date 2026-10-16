package one.meshcore.shared.settings.ui

import one.meshcore.shared.settings.ManualRadioParams
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioManualApplyTest {
    @Test
    fun manualApplyKeepsUnsetPowerAndRepeatAndDropsAMismatchedPreset() {
        val updated = radioSnapshotAfterManualApply(
            sampleSnapshot(txPower = 10, clientRepeat = false, presetId = "not-a-preset"),
            ManualRadioParams(
                frequencyMHz = 910.5,
                bandwidthKHz = 62.5,
                spreadingFactor = 8,
                codingRate = 6,
                txPower = null,
                clientRepeat = null,
            ),
        )
        assertEquals(910_500L, updated.frequencyKHz)
        assertEquals(62_500L, updated.bandwidthHz)
        assertEquals(8, updated.spreadingFactor)
        assertEquals(6, updated.codingRate)
        assertEquals(10, updated.txPower)
        assertEquals(false, updated.clientRepeat)
        assertNull(updated.appliedRadioPresetID)
    }

    @Test
    fun manualApplyKeepsThePresetNameWhileRepeatIsOn() {
        val updated = radioSnapshotAfterManualApply(
            sampleSnapshot(txPower = 10, clientRepeat = false, presetId = "not-a-preset"),
            ManualRadioParams(
                frequencyMHz = 910.5,
                bandwidthKHz = 62.5,
                spreadingFactor = 8,
                codingRate = 6,
                txPower = 22,
                clientRepeat = true,
            ),
        )
        assertEquals(22, updated.txPower)
        assertEquals(true, updated.clientRepeat)
        assertEquals("not-a-preset", updated.appliedRadioPresetID)
    }

    @Test
    fun presetSectionTitleUsesTheCatalogName() {
        val preset = RadioPresets.repeatPresets.first()
        assertEquals(preset.name, radioPresetSectionTitle(preset, "Custom"))
        assertEquals("Custom", radioPresetSectionTitle(null, "Custom"))
    }

    private fun sampleSnapshot(txPower: Int, clientRepeat: Boolean, presetId: String) = RadioDeviceSnapshot(
        frequencyKHz = 1,
        bandwidthHz = 1,
        spreadingFactor = 7,
        codingRate = 5,
        txPower = txPower,
        clientRepeat = clientRepeat,
        appliedRadioPresetID = presetId,
    )
}
