package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The in-app permission path: a user who declined during onboarding is prompted again when they open a
 * screen that needs location, and only once.
 */
class LocationPermissionPromptTest {
    @Test
    fun ungrantedAndUnaskedPrompts() {
        assertTrue(
            shouldPromptForLocation(LocationAuthorizationStatus.NOT_DETERMINED, hasPrompted = false),
        )
        assertFalse(shouldPromptForLocation(LocationAuthorizationStatus.DENIED, hasPrompted = false))
    }

    @Test
    fun anAuthorizedGrantNeverPrompts() {
        assertFalse(
            shouldPromptForLocation(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE, hasPrompted = false),
        )
        assertFalse(
            shouldPromptForLocation(LocationAuthorizationStatus.AUTHORIZED_ALWAYS, hasPrompted = false),
        )
    }

    @Test
    fun reenteringTheScreenDoesNotPromptAgain() {
        assertFalse(
            shouldPromptForLocation(LocationAuthorizationStatus.NOT_DETERMINED, hasPrompted = true),
        )
    }
}
