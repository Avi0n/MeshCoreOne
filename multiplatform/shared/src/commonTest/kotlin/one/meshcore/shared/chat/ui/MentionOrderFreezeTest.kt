package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MentionOrderFreezeTest {
    @Test
    fun startingAQuerySnapshotsTheLiveOrder() {
        val live = mapOf("Alice" to 2u, "Bob" to 1u)
        assertEquals(live, nextFrozenMentionOrder(queryActive = true, frozen = null, live = live))
    }

    @Test
    fun anActiveQueryKeepsTheFrozenOrderWhenLiveChanges() {
        val frozen = mapOf("Alice" to 2u)
        val live = mapOf("Alice" to 2u, "Carol" to 9u)
        assertSame(frozen, nextFrozenMentionOrder(queryActive = true, frozen = frozen, live = live))
    }

    @Test
    fun endingTheQueryClearsTheFreeze() {
        assertNull(
            nextFrozenMentionOrder(
                queryActive = false,
                frozen = mapOf("Alice" to 2u),
                live = mapOf("Alice" to 2u),
            ),
        )
    }
}
