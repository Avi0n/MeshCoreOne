package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.chat.MessageFragmentBuilder
import one.meshcore.shared.chat.buildInputs
import one.meshcore.shared.chat.text.LinkTarget
import one.meshcore.shared.chat.text.MessageLinkAccessibility
import one.meshcore.shared.chat.text.MessageTextLinkifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The link actions a bubble offers a screen-reader user. The body text renders as one merged node, so a
 * link that contributes no action here cannot be activated by TalkBack at all.
 */
class MessageLinkActionTargetsTest {
    private val env = EnvInputs(currentUserName = "Me", showIncomingSendTime = false)

    private fun item(text: String) = ChatFixtures.message(text = text).let { message ->
        val inputs = buildInputs(message.id).copy(
            formattedText = MessageTextLinkifier.linkify(message.text, currentUserName = null).formatted,
        )
        MessageFragmentBuilder.makeItem(message, inputs, env)
    }

    @Test
    fun `a web link in the body becomes an open action naming its host`() {
        val targets = messageLinkActionTargets(item("status at https://example.org/ops please read"))

        assertEquals(1, targets.size)
        assertEquals(MessageLinkAccessibility.Label.OpenWebLink("example.org"), targets.single().first)
        assertEquals(LinkTarget.Web("https://example.org/ops"), targets.single().second)
    }

    @Test
    fun `a message with no link offers no link action`() {
        assertTrue(messageLinkActionTargets(item("no links here")).isEmpty())
    }

    @Test
    fun `two links stay separately activatable`() {
        val targets = messageLinkActionTargets(item("https://one.example/a and https://two.example/b"))

        assertEquals(
            listOf(
                MessageLinkAccessibility.Label.OpenWebLink("one.example"),
                MessageLinkAccessibility.Label.OpenWebLink("two.example"),
            ),
            targets.map { it.first },
        )
    }

    @Test
    fun `a room body uses the same link actions as a chat bubble`() {
        val text = "status at https://example.org/ops please read"
        val formatted = MessageTextLinkifier.linkify(text, currentUserName = null).formatted
        val fromRoom = formattedTextLinkActionTargets(formatted)
        val fromChat = messageLinkActionTargets(item(text))

        assertEquals(fromChat, fromRoom)
        assertEquals(LinkTarget.Web("https://example.org/ops"), fromRoom.single().second)
    }
}
