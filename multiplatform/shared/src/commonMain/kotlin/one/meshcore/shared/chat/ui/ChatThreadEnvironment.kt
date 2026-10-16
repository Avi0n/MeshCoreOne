package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.emptyFlow
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.chat.IncomingMessageEvent
import one.meshcore.shared.chat.MessageStatusEvent

/** Live connection providers alongside app-owned offline stores and retained draft editors. */
data class ChatThreadEnvironment(
    val graph: ChatThreadGraphInputs,
    val display: ChatThreadDisplayInputs = ChatThreadDisplayInputs(),
    val people: ChatThreadPeopleInputs = ChatThreadPeopleInputs(),
    val share: ChatThreadShareInputs = ChatThreadShareInputs(),
    val flood: ChatThreadFloodInputs = ChatThreadFloodInputs(),
    val lifecycle: ChatThreadLifecycleInputs = ChatThreadLifecycleInputs(),
) : ChatThreadGraphHost by graph,
    ChatThreadDisplayHost by display,
    ChatThreadPeople by people,
    ChatThreadShareActions by share,
    ChatThreadFloodScope by flood,
    ChatThreadLifecycle by lifecycle {

    companion object {
        /** No radio graph. Sends and sheets stay inert because every provider is absent. */
        val Disconnected = ChatThreadEnvironment(
            graph = ChatThreadGraphInputs(
                registry = { null },
                store = { null },
                sendQueue = { null },
                messageService = { null },
                draftEditors = { null },
                teardownScope = { disconnectedTeardownScope },
                currentContact = { null },
                unreadCount = { 0 },
                localNodeName = { null },
                incomingEvents = { emptyFlow<IncomingMessageEvent>() },
                statusEvents = { emptyFlow<MessageStatusEvent>() },
            ),
        )

        private val disconnectedTeardownScope = CoroutineScope(SupervisorJob())
    }
}

/** Consumes a parked reply once; retained unsent text remains available on later presentations. */
internal suspend fun ChatThreadEnvironment.restoreComposerDraft(
    composer: ComposerViewModel,
    conversationID: ChatConversationID,
) {
    if (!composer.acceptsDraft) return
    val reply = consumeQuickReplyDraft(conversationID) { composer.acceptsDraft }
    composer.restoreDraft(reply)
}
