package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Channel `@` suggestions keep the iOS overlay geometry (32-pt avatar, 200-pt cap, springs)
 * with a Material tonal surface instead of an iOS drop shadow.
 */
class MentionSuggestionLayoutTest {
    @Test
    fun mentionSuggestionAvatarAndCapMatchIos() {
        assertEquals(32, MENTION_SUGGESTION_AVATAR_DP)
        assertEquals(12, MENTION_SUGGESTION_ROW_SPACING_DP)
        assertEquals(200, MENTION_SUGGESTION_MAX_HEIGHT_DP)
        assertEquals(48, MENTION_SUGGESTION_ROW_HEIGHT_DP)
        assertEquals(12, MENTION_SUGGESTION_CORNER_DP)
        assertEquals(16, MENTION_SUGGESTION_HORIZONTAL_PADDING_DP)
        assertEquals(3, MENTION_SUGGESTION_TONAL_ELEVATION_DP)
        assertEquals(20, MENTION_SUGGESTION_LIMIT)
    }

    @Test
    fun contentHeightUsesRowHeightWithoutHairlines() {
        assertEquals(0, mentionSuggestionContentHeightDp(0))
        assertEquals(48, mentionSuggestionContentHeightDp(1))
        assertEquals(96, mentionSuggestionContentHeightDp(2))
        assertEquals(192, mentionSuggestionContentHeightDp(4))
        assertEquals(200, mentionSuggestionContentHeightDp(5))
        assertEquals(200, mentionSuggestionContentHeightDp(20))
        assertEquals(200, mentionSuggestionContentHeightDp(21))
    }

    @Test
    fun insertAndHeightSpringsMatchIosResponse() {
        assertEquals(0.95f, MENTION_OVERLAY_INSERT_SCALE)
        assertEquals(0.8f, MENTION_OVERLAY_INSERT_SPRING_DAMPING)
        assertEquals(0.9f, MENTION_OVERLAY_HEIGHT_SPRING_DAMPING)
        assertEquals(438.65f, MENTION_OVERLAY_INSERT_SPRING_STIFFNESS, absoluteTolerance = 0.01f)
        assertEquals(631.65f, MENTION_OVERLAY_HEIGHT_SPRING_STIFFNESS, absoluteTolerance = 0.01f)
    }
}
