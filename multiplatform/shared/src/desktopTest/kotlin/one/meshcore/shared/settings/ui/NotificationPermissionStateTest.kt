package one.meshcore.shared.settings.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The three arms iOS's `NotificationSettingsSection` switches on, which Kotlin previously had two of. */
class NotificationPermissionStateTest {
    @Test
    fun aUserNeverAskedIsOfferedThePromptRatherThanSystemSettings() {
        assertEquals(
            NotificationPermissionState.NOT_ASKED,
            notificationPermission(authorized = false, determined = false),
        )
    }

    @Test
    fun aUserWhoRefusedCanOnlyBeSentToSystemSettings() {
        assertEquals(
            NotificationPermissionState.DENIED,
            notificationPermission(authorized = false, determined = true),
        )
    }

    @Test
    fun aGrantedPermissionShowsTheToggles() {
        assertEquals(
            NotificationPermissionState.GRANTED,
            notificationPermission(authorized = true, determined = true),
        )
    }
}
