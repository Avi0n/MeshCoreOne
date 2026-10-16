package one.meshcore.shared.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class PreferenceHubChromeTest {
    @Test
    fun groupInsetsMatchSystemSettingsDensity() {
        assertEquals(16.dp, PreferenceHubHorizontalPadding)
        assertEquals(16.dp, PreferenceHubSectionGap)
        assertEquals(16.dp, PreferenceHubCardPadding)
        assertEquals(16.dp, PreferenceHubFooterInset)
        assertEquals(8.dp, PreferenceHubTopPadding)
        assertEquals(6.dp, PreferenceHubBulletMarkerSize)
        assertEquals(12.dp, PreferenceHubBulletGap)
        assertEquals(4.dp, PreferenceHubBulletRowGap)
        assertEquals(7.dp, PreferenceHubBulletMarkerTop)
    }
}
