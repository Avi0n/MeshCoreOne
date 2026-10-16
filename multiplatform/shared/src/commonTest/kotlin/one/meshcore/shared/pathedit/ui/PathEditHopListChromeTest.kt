package one.meshcore.shared.pathedit.ui

import one.meshcore.shared.ui.PreferenceHubCardPadding
import kotlin.test.Test
import kotlin.test.assertEquals

class PathEditHopListChromeTest {
    @Test
    fun hopRowsSitAtPreferenceHubCardDensity() {
        assertEquals(PreferenceHubCardPadding, PathEditMetrics.rowInset)
    }
}
