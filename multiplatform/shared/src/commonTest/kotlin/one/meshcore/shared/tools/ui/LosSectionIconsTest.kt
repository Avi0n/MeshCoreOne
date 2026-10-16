package one.meshcore.shared.tools.ui

import androidx.compose.ui.graphics.Color
import one.meshcore.shared.ui.SignalChromeRole
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class LosSectionIconsTest {
    @Test
    fun rfSettingsUsesTheAntennaIcon() {
        assertEquals(AppIcons.CellTower, LOS_RF_SETTINGS_ICON)
    }

    @Test
    fun refractionAndCurvatureUseTheGlobe() {
        assertEquals(AppIcons.Public, LOS_REFRACTION_ICON)
        assertEquals(AppIcons.Public, LOS_EARTH_CURVATURE_ICON)
    }

    @Test
    fun emptyProfileUsesTheLineChartIcon() {
        assertEquals(AppIcons.ShowChart, LOS_EMPTY_CHART_ICON)
    }

    @Test
    fun pointActionsMatchIosPointRowButtonsView() {
        assertEquals(AppIcons.Share, LOS_POINT_SHARE_ICON)
        assertEquals(AppIcons.LocationOn, LOS_POINT_RELOCATE_ICON)
        assertEquals(AppIcons.Straighten, LOS_POINT_EDIT_ICON)
        assertEquals(AppIcons.Check, LOS_POINT_DONE_ICON)
        assertEquals(AppIcons.Close, LOS_POINT_CLEAR_ICON)
    }

    @Test
    fun analyzeFrequencyAndElevationUseIosGlyphs() {
        assertEquals(AppIcons.GraphicEq, LOS_ANALYZE_ICON)
        assertEquals(AppIcons.CellTower, LOS_FREQUENCY_ICON)
        assertEquals(AppIcons.CheckCircle, LOS_FREQUENCY_COMMIT_ICON)
        assertEquals(AppIcons.Warning, LOS_ELEVATION_UNAVAILABLE_ICON)
    }

    @Test
    fun analyzeAndRfStayHiddenUntilElevationsExist() {
        assertEquals(false, shouldShowLosAnalyzeButton(canAnalyze = false, hasResult = false, isError = false))
        assertEquals(false, shouldShowLosRfSettingsBeforeResult(canAnalyze = false, hasResult = false, isError = false))
        assertEquals(true, shouldShowLosAnalyzeButton(canAnalyze = true, hasResult = false, isError = false))
        assertEquals(true, shouldShowLosRfSettingsBeforeResult(canAnalyze = true, hasResult = false, isError = false))
        assertEquals(true, shouldShowLosAnalyzeButton(canAnalyze = true, hasResult = true, isError = false))
        assertEquals(false, shouldShowLosRfSettingsBeforeResult(canAnalyze = true, hasResult = true, isError = false))
        assertEquals(false, shouldShowLosAnalyzeButton(canAnalyze = true, hasResult = false, isError = true))
        assertEquals(false, shouldShowLosRfSettingsBeforeResult(canAnalyze = true, hasResult = false, isError = true))
    }

    @Test
    fun pointRowMarkersUseThemeRoles() {
        assertEquals(SignalChromeRole.PRIMARY, LOS_ENDPOINT_A_ROLE)
        assertEquals(SignalChromeRole.TERTIARY, LOS_ENDPOINT_B_ROLE)
        assertEquals(SignalChromeRole.SECONDARY, LOS_REPEATER_ROLE)
        assertEquals(SignalChromeRole.TERTIARY, LOS_FRESNEL_CLEAR_ROLE)
        assertEquals(SignalChromeRole.ERROR, LOS_FRESNEL_OBSTRUCTED_ROLE)
        assertEquals(AppIcons.AddCircle, LOS_ADD_REPEATER_ICON)
    }

    @Test
    fun pointMarkerLabelsUseOnFillNotHardcodedWhite() {
        val onFill = Color(0xFF21005D)
        val onSurface = Color(0xFF1C1B1F)
        assertEquals(onFill, losPointMarkerLabelColor(filled = true, onFill = onFill, onSurface = onSurface))
        assertEquals(onSurface, losPointMarkerLabelColor(filled = false, onFill = onFill, onSurface = onSurface))
        assertEquals(onSurface.copy(alpha = LOS_POINT_MARKER_EMPTY_FILL_ALPHA), losPointMarkerEmptyFill(onSurface))
    }
}
