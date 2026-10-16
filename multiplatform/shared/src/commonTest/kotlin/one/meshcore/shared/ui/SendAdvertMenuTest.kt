package one.meshcore.shared.ui

import one.meshcore.shared.navigation.SendAdvertShortcutOutcome
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Toolbar Send Advert disables both rows while a send is in flight and treats only [SendAdvertShortcutOutcome.Sent]
 * as success haptic, matching iOS `BLEStatusIndicatorView`.
 */
class SendAdvertMenuTest {
    @Test
    fun advertRowsDisableWhileSendingOrNotReady() {
        assertTrue(advertRowsEnabled(isReady = true, isSendingAdvert = false))
        assertFalse(advertRowsEnabled(isReady = true, isSendingAdvert = true))
        assertFalse(advertRowsEnabled(isReady = false, isSendingAdvert = false))
    }

    @Test
    fun onlyASentAdvertIsSuccessHaptic() {
        assertTrue(advertSendSucceeded(SendAdvertShortcutOutcome.Sent))
        assertFalse(advertSendSucceeded(SendAdvertShortcutOutcome.Failed))
        assertFalse(advertSendSucceeded(SendAdvertShortcutOutcome.NotConnected))
    }
}
