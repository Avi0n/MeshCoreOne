package one.meshcore.shared.contact.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `ContactDetailAvatarView` VoiceOver speaks name then choose-source or saving. */
class ContactDetailAvatarTalkBackTest {
    @Test
    fun editableAvatarSpeaksNameThenAction() {
        assertEquals(
            "Alice, Choose photo source",
            editableContactAvatarContentDescription(
                displayName = "Alice",
                actionLabel = "Choose photo source",
            ),
        )
        assertEquals(
            "Alice, Saving photo",
            editableContactAvatarContentDescription(
                displayName = "Alice",
                actionLabel = "Saving photo",
            ),
        )
    }
}
