package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LineOfSightLayoutTest {
    @Test
    fun pairRequiresBothMeasuredWidthsAndDivider() {
        assertFalse(shouldShowLosSideBySide(640f, 800f))
        assertTrue(shouldShowLosSideBySide(641f, 800f))
    }

    @Test
    fun pairRequiresAnalysisHeightEvenOnWideWorkspace() {
        assertFalse(shouldShowLosSideBySide(1000f, 419f))
        assertTrue(shouldShowLosSideBySide(1000f, 420f))
    }

    @Test
    fun analysisWidthPreservesMapAndCapsAtPreferred() {
        assertEquals(320, losAnalysisPanelWidthDp(641f))
        assertEquals(379, losAnalysisPanelWidthDp(700f))
        assertEquals(400, losAnalysisPanelWidthDp(1000f))
    }

    @Test
    fun compactSheetPeeksAtQuarterOfHost() {
        assertEquals(0.25f, LOS_COMPACT_SHEET_PEEK_FRACTION)
    }
}
