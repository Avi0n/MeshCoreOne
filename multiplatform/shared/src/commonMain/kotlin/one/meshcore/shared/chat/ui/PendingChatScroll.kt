package one.meshcore.shared.chat.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.chat.ChatScrollRequest
import one.meshcore.shared.model.Uuid

/** The shell owns pending notification requests; a thread consumes only the request it can show. */
interface PendingChatScroll {
    val requests: StateFlow<ChatScrollRequest?>
    fun peek(): ChatScrollRequest?
    fun consume(requestID: Uuid): Boolean
}

/** Bind route kind and identity before the pane resolves its protocol conversation key. */
fun PendingChatScroll.forConversation(conversationID: String): PendingChatScroll = object : PendingChatScroll {
    override val requests: StateFlow<ChatScrollRequest?> = this@forConversation.requests
    override fun peek(): ChatScrollRequest? = this@forConversation.peek()?.takeIf {
        it.conversationID == conversationID
    }
    override fun consume(requestID: Uuid): Boolean =
        peek()?.requestID == requestID && this@forConversation.consume(requestID)
}

val LocalPendingChatScroll: ProvidableCompositionLocal<PendingChatScroll> =
    staticCompositionLocalOf { error("The shell must provide a PendingChatScroll above the chat thread") }
