package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class AboutSectionIconTest {
    @Test
    fun aboutIconsMatchIosAboutSection() {
        assertEquals(AppIcons.Favorite, ABOUT_DONATION_ICON)
        assertEquals(AppIcons.Feedback, ABOUT_FEEDBACK_ICON)
        assertEquals(AppIcons.Public, ABOUT_WEBSITE_ICON)
        assertEquals(AppIcons.Map, ABOUT_MAP_ICON)
        assertEquals(AppIcons.Code, ABOUT_GITHUB_ICON)
        assertEquals(AppIcons.FrontHand, ABOUT_PRIVACY_ICON)
    }

    @Test
    fun versionFooterJoinsWithACommaNotAMiddot() {
        assertEquals("v1.4.0, 42", joinVersionFooterParts("v1.4.0", "42"))
        assertEquals("v1.4.0", joinVersionFooterParts("v1.4.0", null))
    }
}
