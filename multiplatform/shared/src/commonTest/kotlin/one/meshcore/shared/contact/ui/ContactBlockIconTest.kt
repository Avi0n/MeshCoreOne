package one.meshcore.shared.contact.ui

import one.meshcore.shared.settings.ui.BLOCKING_CHANNEL_SENDERS_ICON
import one.meshcore.shared.settings.ui.BLOCKING_CONTACTS_ICON
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactBlockIconTest {
    @Test
    fun raisedHandFamilyMatchesIosHandRaisedSymbols() {
        assertEquals(AppIcons.FrontHand, blockToggleIcon(isBlocked = false))
        assertEquals(AppIcons.DoNotTouch, blockToggleIcon(isBlocked = true))
    }

    @Test
    fun settingsBlockingRowsMatchIosBlockingSection() {
        assertEquals(AppIcons.DoNotTouch, BLOCKING_CONTACTS_ICON)
        assertEquals(AppIcons.NoAccounts, BLOCKING_CHANNEL_SENDERS_ICON)
    }
}
