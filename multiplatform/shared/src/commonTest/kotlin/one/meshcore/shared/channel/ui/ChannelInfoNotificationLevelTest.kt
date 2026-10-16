package one.meshcore.shared.channel.ui

import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_notification_level_accessibility_all
import one.meshcore.shared.resources.chats_notification_level_accessibility_mentions_only
import one.meshcore.shared.resources.chats_notification_level_accessibility_muted
import one.meshcore.shared.resources.chats_notification_level_hint
import kotlin.test.Test
import kotlin.test.assertEquals

/** iOS `NotificationLevelPicker` speaks the consequence, not the one-word chip. */
class ChannelInfoNotificationLevelTest {
    @Test
    fun chipsSpeakTheNotificationConsequenceNotTheShortLabel() {
        assertEquals(
            Res.string.chats_notification_level_accessibility_muted,
            NotificationLevel.MUTED.notificationAccessibilityDescriptionRes(),
        )
        assertEquals(
            Res.string.chats_notification_level_accessibility_mentions_only,
            NotificationLevel.MENTIONS_ONLY.notificationAccessibilityDescriptionRes(),
        )
        assertEquals(
            Res.string.chats_notification_level_accessibility_all,
            NotificationLevel.ALL.notificationAccessibilityDescriptionRes(),
        )
        assertEquals(Res.string.chats_notification_level_hint, notificationLevelPickerHintRes())
    }
}
