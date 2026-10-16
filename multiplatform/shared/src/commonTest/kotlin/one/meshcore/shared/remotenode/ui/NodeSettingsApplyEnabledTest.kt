package one.meshcore.shared.remotenode.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Change Password Apply, matching iOS `NodeSecuritySection`. */
class NodeSettingsApplyEnabledTest {
    @Test
    fun passwordChangeRequiresMatchingNonEmptyFieldsAndIdle() {
        assertTrue(passwordChangeApplyEnabled("secret", "secret", isChanging = false))
        assertFalse(passwordChangeApplyEnabled("", "", isChanging = false))
        assertFalse(passwordChangeApplyEnabled("secret", "", isChanging = false))
        assertFalse(passwordChangeApplyEnabled("a", "b", isChanging = false))
        assertFalse(passwordChangeApplyEnabled("secret", "secret", isChanging = true))
    }
}
