package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConversationSectionsTest {
    @Test
    fun noPinsStayAFlatList() {
        val others = listOf(direct("Ada"), direct("Bea"))
        val sections = conversationSections(pinned = emptyList(), others = others)
        val rowIds = sections.filterIsInstance<ConversationSection.Row>().map { row -> row.conversation.id }
        assertEquals(others.map { conversation -> conversation.id }, rowIds)
        assertTrue(sections.none { it is ConversationSection.Header })
    }

    @Test
    fun pinsAddBothHeadingsAndKeepOrder() {
        val pinned = listOf(direct("Pin"))
        val others = listOf(direct("Other"))
        val sections = conversationSections(pinned, others)
        assertIs<ConversationSection.Header>(sections[0]).also {
            assertEquals(ConversationSection.Kind.PINNED, it.kind)
        }
        assertEquals(pinned.single().id, assertIs<ConversationSection.Row>(sections[1]).conversation.id)
        assertEquals(ConversationSection.Kind.OTHER, assertIs<ConversationSection.Header>(sections[2]).kind)
        assertEquals(others.single().id, assertIs<ConversationSection.Row>(sections[3]).conversation.id)
    }

    @Test
    fun pinsWithoutOtherRowsOmitTheSecondHeading() {
        val sections = conversationSections(pinned = listOf(direct("Only")), others = emptyList())
        assertEquals(2, sections.size)
        assertEquals(ConversationSection.Kind.PINNED, assertIs<ConversationSection.Header>(sections[0]).kind)
        assertIs<ConversationSection.Row>(sections[1])
    }

    private fun direct(name: String) = Conversation.Direct(ChatFixtures.contact(name = name, isPinned = true))
}
