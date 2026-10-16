package one.meshcore.shared.battery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryOcvTest {
    private val liIon = OCVPreset.LI_ION.ocvArray

    @Test
    fun voltageAt100PointReturns100() {
        assertEquals(100, BatteryOcv.percentage(4190, liIon))
    }

    @Test
    fun voltageAt0PointReturns0() {
        assertEquals(0, BatteryOcv.percentage(3100, liIon))
    }

    @Test
    fun voltageAboveMaxReturns100() {
        assertEquals(100, BatteryOcv.percentage(4500, liIon))
    }

    @Test
    fun voltageBelowMinReturns0() {
        assertEquals(0, BatteryOcv.percentage(2800, liIon))
    }

    @Test
    fun voltageAt50PointReturns50() {
        assertEquals(50, BatteryOcv.percentage(3720, liIon))
    }

    @Test
    fun voltageInterpolatesBetweenPoints() {
        // Midpoint between 4190 (100%) and 4050 (90%) ≈ 95%.
        val percent = BatteryOcv.percentage(4120, liIon)
        assertTrue(percent in 94..96, "Expected ~95%, got $percent")
    }

    @Test
    fun loadEditorStateCustomArray() {
        val custom = listOf(4000, 3900, 3800, 3700, 3600, 3500, 3400, 3300, 3200, 3100, 3000)
        val state = BatteryOcv.loadEditorState(
            OCVPreset.CUSTOM.rawValue,
            BatteryOcv.encodeCustomArray(custom),
        )
        assertEquals(OCVPreset.CUSTOM, state.preset)
        assertEquals(custom, state.values)
    }

    @Test
    fun loadEditorStateNamedPreset() {
        val state = BatteryOcv.loadEditorState(OCVPreset.LI_FE_PO4.rawValue, null)
        assertEquals(OCVPreset.LI_FE_PO4, state.preset)
        assertEquals(OCVPreset.LI_FE_PO4.ocvArray, state.values)
    }

    @Test
    fun validationRejectsAscendingSegment() {
        val bad = listOf(4190, 4050, 4100, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)
        assertEquals("not_descending", BatteryOcv.validationErrorKey(bad))
    }

    @Test
    fun validationRejectsOutOfRange() {
        val bad = listOf(4190, 4050, 3990, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 50)
        assertEquals("out_of_range:0", BatteryOcv.validationErrorKey(bad))
    }

    @Test
    fun validationAcceptsLiIon() {
        assertNull(BatteryOcv.validationErrorKey(liIon))
    }

    @Test
    fun nodePresetsIncludeChemistryAndSeeedSolar() {
        val presets = OCVPreset.nodePresets
        assertTrue(presets.contains(OCVPreset.LI_ION))
        assertTrue(presets.contains(OCVPreset.SEEED_SOLAR_NODE))
        assertTrue(presets.none { it == OCVPreset.CUSTOM })
    }

    @Test
    fun selectablePresetsExcludeCustomAndCoverAllNamed() {
        val selectable = OCVPreset.selectablePresets
        assertTrue(selectable.none { it == OCVPreset.CUSTOM })
        assertEquals(OCVPreset.entries.size - 1, selectable.size)
        assertTrue(selectable.contains(OCVPreset.LI_ION))
        assertTrue(selectable.contains(OCVPreset.THINK_NODE_M6))
    }

    @Test
    fun fieldHasErrorFalseForValidLiIonPoints() {
        liIon.indices.forEach { index ->
            assertFalse(
                BatteryOcvGrid.fieldHasError(index, liIon),
                "index $index should be valid on Li-Ion",
            )
        }
    }

    @Test
    fun fieldHasErrorTrueWhenAscendingWithPrevious() {
        val bad = listOf(4190, 4050, 4100, 3890, 3800, 3720, 3630, 3530, 3420, 3300, 3100)
        assertTrue(BatteryOcvGrid.fieldHasError(2, bad))
        assertTrue(BatteryOcvGrid.fieldHasError(1, bad))
    }

    @Test
    fun fieldHasErrorTrueWhenOutOfRange() {
        val bad = liIon.toMutableList().also { it[0] = 50 }
        assertTrue(BatteryOcvGrid.fieldHasError(0, bad))
    }

    @Test
    fun percentAtIndexMapsHundredToZero() {
        assertEquals(100, BatteryOcvGrid.percentAtIndex(0))
        assertEquals(50, BatteryOcvGrid.percentAtIndex(5))
        assertEquals(0, BatteryOcvGrid.percentAtIndex(10))
    }
}
