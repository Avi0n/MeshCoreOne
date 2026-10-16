package one.meshcore.shared.settings.ui

import one.meshcore.shared.navigation.SettingsDestination
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Feedback contact targets match iOS `FeedbackView.FeedbackContact` so GitHub Issues and the
 * developer mailbox stay one pair of URLs across platforms.
 */
class FeedbackContactTest {
    @Test
    fun githubIssuesUrlMatchesIos() {
        assertEquals("https://github.com/Avi0n/MeshCoreOne/issues", FEEDBACK_GITHUB_ISSUES_URL)
    }

    @Test
    fun emailUsesTheSameDeveloperMailboxAsSupport() {
        assertEquals("mailto:info@meshcoreone.com", SUPPORT_CONTACT_MAILTO)
    }

    @Test
    fun feedbackDoesNotRequireAConnectedRadio() {
        assertFalse(SettingsDestination.FEEDBACK.requiresDevice)
    }
}
