// AndroidOnly: WP-318 Appearance scheme radio rows keep the 48dp minimum at 200% font (issue #231)
package com.meshcoreone.android.feature.settings.app.appearance.ui

import kotlin.test.assertEquals
import org.junit.Test

class AppearanceSchemeRowTargetTest {
    @Test
    fun schemeRowsAllocateTheFullMinimumTouchTarget() {
        assertEquals(48, SCHEME_ROW_MINIMUM_HEIGHT_DP)
    }
}
