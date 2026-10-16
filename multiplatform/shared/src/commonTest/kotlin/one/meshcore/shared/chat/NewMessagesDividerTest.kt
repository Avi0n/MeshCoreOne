package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Mirrors the Swift `ChatViewModel` divider tests (spec §9.4). */
class NewMessagesDividerTest {
    private fun rows(count: Int): List<MessageDto> = (0 until count).map {
        ChatFixtures.dmMessage(timestamp = 1_000L + it, createdAt = ChatFixtures.BASE_EPOCH_SECONDS + it)
    }

    @Test
    fun `no divider without an unread backlog`() {
        assertNull(computeNewMessagesDivider(rows(20), unreadCount = 0, isDM = true))
    }

    @Test
    fun `divider shows for a single unread message`() {
        val messages = rows(20)
        assertEquals(messages[19].id, computeNewMessagesDivider(messages, unreadCount = 1, isDM = true))
    }

    @Test
    fun `divider shows for a backlog smaller than one screen`() {
        // iOS draws the line for any unread backlog; five unread must not be silently dropped.
        val messages = rows(20)
        assertEquals(messages[15].id, computeNewMessagesDivider(messages, unreadCount = 5, isDM = true))
    }

    @Test
    fun `divider lands on the first unread row of the recent block`() {
        // 8 read + 12 unread (unread occupy the tail); divider sits 12 rows from the end -> index 8.
        val messages = rows(20)
        val id = computeNewMessagesDivider(messages, unreadCount = 12, isDM = true)
        assertEquals(messages[8].id, id)
    }

    @Test
    fun `divider shows at exactly eleven unread`() {
        val messages = rows(20)
        val id = computeNewMessagesDivider(messages, unreadCount = 11, isDM = true)
        assertEquals(messages[9].id, id)
    }

    @Test
    fun `divider clamps to the oldest loaded row when unread exceeds the page`() {
        val messages = rows(30)
        val id = computeNewMessagesDivider(messages, unreadCount = 50, isDM = true)
        assertEquals(messages[0].id, id)
    }

    @Test
    fun `divider advances past a hidden outgoing reaction at the boundary`() {
        // Boundary row (index size-unread) is a sent outgoing "reaction"; the divider advances to the next row.
        val messages = rows(20).toMutableList()
        val boundary = 8
        messages[boundary] = ChatFixtures.message(
            id = messages[boundary].id,
            text = "👍",
            timestamp = 1_000L + boundary,
            createdAt = ChatFixtures.BASE_EPOCH_SECONDS + boundary,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.SENT,
        )
        val id = computeNewMessagesDivider(
            messages,
            unreadCount = 12,
            isDM = true,
            isHiddenReaction = { m, _ -> isHiddenOutgoingReaction(m) { it.text == "👍" } },
        )
        assertEquals(messages[boundary + 1].id, id)
    }

    @Test
    fun `hidden outgoing reaction keeps a failed reaction visible`() {
        val failed = ChatFixtures.message(
            text = "👍",
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.FAILED,
        )
        assertEquals(false, isHiddenOutgoingReaction(failed) { true })
    }
}
