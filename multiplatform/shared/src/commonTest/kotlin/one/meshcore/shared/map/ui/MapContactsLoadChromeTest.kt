package one.meshcore.shared.map.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapContactsLoadChromeTest {
    @Test
    fun firstPaintAndUserRefreshShowChrome() {
        assertTrue(mapContactsLoadShowsChrome(userInitiated = false, hasCompletedInitialLoad = false))
        assertTrue(mapContactsLoadShowsChrome(userInitiated = true, hasCompletedInitialLoad = true))
    }

    @Test
    fun advertDrivenReloadsStayQuietAfterTheFirstPaint() {
        assertFalse(mapContactsLoadShowsChrome(userInitiated = false, hasCompletedInitialLoad = true))
    }

    @Test
    fun loadingScrimUsesThemeScrimNotHardcodedBlack() {
        val scrim = Color(0xFF000000)
        assertEquals(scrim.copy(alpha = MAP_LOADING_SCRIM_ALPHA), mapContactsLoadingScrim(scrim))
    }
}
