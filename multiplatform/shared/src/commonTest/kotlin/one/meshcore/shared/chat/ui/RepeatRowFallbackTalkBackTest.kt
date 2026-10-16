package one.meshcore.shared.chat.ui

import one.meshcore.shared.remotenode.ui.POSSIBLE_MATCH_BUTTON_DP
import one.meshcore.shared.remotenode.ui.POSSIBLE_MATCH_ICON
import one.meshcore.shared.remotenode.ui.possibleMatchMarkerHintRes
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_possible_match_explanation
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS `RepeatRowView` keeps the fallback “?” as its own VoiceOver stop. */
class RepeatRowFallbackTalkBackTest {
    @Test
    fun aFallbackRowDoesNotSwallowThePossibleMatchMarker() {
        assertTrue(repeatRowMergesDescendants(isFallback = false))
        assertFalse(repeatRowMergesDescendants(isFallback = true))
    }

    @Test
    fun thePossibleMatchMarkerSpeaksTheExplanationHint() {
        assertEquals(
            Res.string.remotenodes_remote_nodes_status_possible_match_explanation,
            possibleMatchMarkerHintRes(),
        )
        assertEquals(AppIcons.Info, POSSIBLE_MATCH_ICON)
    }

    @Test
    fun thePossibleMatchMarkerKeepsA48DpTarget() {
        assertEquals(48, POSSIBLE_MATCH_BUTTON_DP)
    }
}
