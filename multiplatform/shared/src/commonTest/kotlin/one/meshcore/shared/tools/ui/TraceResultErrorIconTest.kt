package one.meshcore.shared.tools.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class TraceResultErrorIconTest {
    @Test
    fun usesWarningTriangle() {
        assertEquals(AppIcons.Warning, TRACE_RESULT_ERROR_ICON)
    }

    @Test
    fun emptyPathUsesTheRouteGlyph() {
        assertEquals(AppIcons.Route, TRACE_PATH_EMPTY_ICON)
    }
}
