package one.meshcore.shared.onboarding

import one.meshcore.shared.ui.OnboardingPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingScaffoldChromeTest {
    @Test
    fun wizardWidthCapsAtMaterialExpandedBody() {
        assertEquals(840, ONBOARDING_CONTENT_MAX_WIDTH.value.toInt())
    }

    @Test
    fun notificationsAndLocationAreOptionalBluetoothIsRequired() {
        assertTrue(onboardingPermissionIsOptional(OnboardingPermission.NOTIFICATIONS))
        assertTrue(onboardingPermissionIsOptional(OnboardingPermission.LOCATION))
        assertFalse(onboardingPermissionIsOptional(OnboardingPermission.BLUETOOTH))
    }
}
