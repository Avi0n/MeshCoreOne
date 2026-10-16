package one.meshcore.shared.chat.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatPersonEmptyIconTest {
    @Test
    fun mentionPickerSelfUsesCropCircleAnalog() {
        assertEquals(AppIcons.AccountCircle, mentionPickerEmptyIcon(isSelfMention = true))
    }

    @Test
    fun mentionPickerNotSavedUsesQuestionPersonAnalog() {
        assertEquals(AppIcons.PersonOff, mentionPickerEmptyIcon(isSelfMention = false))
    }

    @Test
    fun sendDmNoMatchesUsesSameUnknownPersonGlyph() {
        assertEquals(AppIcons.PersonOff, SEND_DM_NO_MATCHES_ICON)
        assertEquals(mentionPickerEmptyIcon(isSelfMention = false), SEND_DM_NO_MATCHES_ICON)
    }
}
