package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Composer send stays visible (enabled by `canSend`) so the action does not disappear between keystrokes.
 */
class ComposerSendButtonTest {
    @Test
    fun composerRowUsesMaterialSpacing() {
        assertEquals(16, COMPOSER_BAR_HORIZONTAL_PADDING_DP)
        assertEquals(8, COMPOSER_CONTROL_GAP_DP)
        assertEquals(4, COMPOSER_SEND_COLUMN_GAP_DP)
        assertEquals(20, COMPOSER_LOCK_ICON_DP)
    }
}
