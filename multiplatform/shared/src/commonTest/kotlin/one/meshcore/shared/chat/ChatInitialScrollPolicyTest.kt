package one.meshcore.shared.chat

import one.meshcore.shared.chat.ChatInitialScrollPolicy.FirstSnapshotDecision
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers the pure first-snapshot decision table: divider-row-on-screen gating, the consume-once
 * latch, and the settled-load escape hatch. Mirrors iOS `ChatInitialScrollPolicyTests`.
 */
class ChatInitialScrollPolicyTest {
    private val divider = Uuid.normalized("11111111-1111-1111-1111-111111111111")

    @Test
    fun dividerTargetPresentsWhenItsBakedRowIsOnScreen() {
        assertEquals(
            FirstSnapshotDecision.Present(divider),
            ChatInitialScrollPolicy.firstSnapshotDecision(
                hasConsumed = false,
                unreadCount = 3,
                initialLoadSettled = true,
                dividerMessageID = divider,
                dividerRowOnScreen = true,
            ),
        )
    }

    @Test
    fun anUnresolvedDividerTargetWithholdsEvenAfterTheLoadSettles() {
        assertEquals(
            FirstSnapshotDecision.Withhold,
            ChatInitialScrollPolicy.firstSnapshotDecision(
                hasConsumed = false,
                unreadCount = 3,
                initialLoadSettled = true,
                dividerMessageID = divider,
                dividerRowOnScreen = false,
            ),
        )
    }

    @Test
    fun noDividerTargetWithholdsUntilTheLoadSettles() {
        assertEquals(
            FirstSnapshotDecision.Withhold,
            ChatInitialScrollPolicy.firstSnapshotDecision(
                hasConsumed = false,
                unreadCount = 3,
                initialLoadSettled = false,
                dividerMessageID = null,
                dividerRowOnScreen = false,
            ),
        )
    }

    @Test
    fun aSettledLoadWithNoDividerTargetPresentsAtTheBottom() {
        assertEquals(
            FirstSnapshotDecision.Present(null),
            ChatInitialScrollPolicy.firstSnapshotDecision(
                hasConsumed = false,
                unreadCount = 3,
                initialLoadSettled = true,
                dividerMessageID = null,
                dividerRowOnScreen = false,
            ),
        )
    }

    @Test
    fun theTargetIsRetiredOnceConsumed() {
        assertEquals(
            FirstSnapshotDecision.Present(null),
            ChatInitialScrollPolicy.firstSnapshotDecision(
                hasConsumed = true,
                unreadCount = 3,
                initialLoadSettled = true,
                dividerMessageID = divider,
                dividerRowOnScreen = true,
            ),
        )
    }

    @Test
    fun noUnreadBacklogPresentsImmediatelyWithNoTarget() {
        assertEquals(
            FirstSnapshotDecision.Present(null),
            ChatInitialScrollPolicy.firstSnapshotDecision(
                hasConsumed = false,
                unreadCount = 0,
                initialLoadSettled = false,
                dividerMessageID = divider,
                dividerRowOnScreen = true,
            ),
        )
    }
}
