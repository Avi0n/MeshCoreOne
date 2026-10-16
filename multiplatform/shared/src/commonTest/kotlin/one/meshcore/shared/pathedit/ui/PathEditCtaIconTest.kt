package one.meshcore.shared.pathedit.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class PathEditCtaIconTest {
    @Test
    fun ctaIconsMatchIosPathEditCtaLabel() {
        assertEquals(AppIcons.AddCircleFill, PATH_EDIT_ADD_HOP_ICON)
        assertEquals(AppIcons.CheckCircle, PATH_EDIT_PATH_FULL_ICON)
        assertEquals(AppIcons.RecordVoiceOver, PATH_EDIT_DIRECT_ICON)
        assertEquals(AppIcons.WifiTethering, PATH_EDIT_FLOOD_ICON)
    }
}
