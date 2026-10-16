package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/**
 * Re-primes a warm [ChatCoordinator] when a message arrives for a conversation that is not currently
 * open. A coordinator created by an earlier open keeps the tail it had at last load: the incoming
 * event stream only feeds the open thread, so a reopen would otherwise paint a stale list. Reloading
 * page 0 from the store at arrival time keeps that tail current. Rooms have no coordinator.
 *
 * Mirrors iOS `ChatPrewarmRefresher`. Skip if the conversation is open (the live view already appends).
 * After the debounce, constructs a [ChatTimelinePrimer] so sender tables, JPEG bytes, and reaction
 * indexing run on the same path as a list-tap prime.
 */
class ChatPrewarmRefresher(
    private val hooks: Hooks,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
) {
    data class Hooks(
        val registry: () -> ChatCoordinatorRegistry?,
        val store: () -> ChatCoordinatorStore?,
        val isConversationActive: suspend (ChatConversationID) -> Boolean,
        val unknownLabel: suspend () -> String,
        /**
         * Re-fetched conversation so [ChatTimelinePrimer.prime] sizes the page from the live
         * unread count. Null skips the refresh (iOS channel miss).
         */
        val conversation: suspend (ChatConversationID) -> Conversation? = { null },
        val loadContacts: suspend (Uuid) -> List<ContactDto> = { emptyList() },
        val indexPage: suspend (Conversation, List<MessageDto>) -> Unit = { _, _ -> },
        val warmPreviews: suspend (Conversation) -> Unit = {},
        val envInputs: suspend () -> EnvInputs = { EnvInputs.DEFAULT },
    )

    private val inFlight = mutableMapOf<ChatConversationID, Job>()
    private val inFlightMutex = Mutex()

    /** Conversation ids with a refresh scheduled; tests assert coalescing on this set. */
    internal suspend fun inFlightIds(): Set<ChatConversationID> = inFlightMutex.withLock { inFlight.keys.toSet() }

    suspend fun noteDirectMessage(radioID: Uuid, contactID: Uuid) {
        schedule(ChatConversationID.dm(radioID, contactID))
    }

    suspend fun noteChannelMessage(radioID: Uuid, channelIndex: Int) {
        schedule(ChatConversationID.channel(radioID, channelIndex))
    }

    private suspend fun schedule(id: ChatConversationID) {
        val scheduled = inFlightMutex.withLock {
            val existing = hooks.registry()?.existingCoordinator(id)
            if (inFlight[id] == null && existing?.renderState?.phase == ChatRenderState.LoadPhase.LOADED) {
                lateinit var job: Job
                job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        if (debounceMs > 0L) delay(debounceMs)
                        refresh(id)
                    } finally {
                        withContext(NonCancellable) {
                            inFlightMutex.withLock {
                                if (inFlight[id] === job) inFlight.remove(id)
                            }
                        }
                    }
                }
                inFlight[id] = job
                job
            } else {
                null
            }
        }
        scheduled?.start()
    }

    /** Cancels and joins every refresh owned by this connection graph. */
    internal suspend fun cancelPending() {
        val pending = inFlightMutex.withLock {
            inFlight.values.toList().also { inFlight.clear() }
        }
        pending.forEach { it.cancel() }
        pending.joinAll()
    }

    private suspend fun refresh(id: ChatConversationID) {
        if (hooks.isConversationActive(id)) return
        val registry = hooks.registry() ?: return
        val existing = registry.existingCoordinator(id) ?: return
        if (existing.renderState.phase != ChatRenderState.LoadPhase.LOADED) return
        val conversation = hooks.conversation(id) ?: return
        ChatTimelinePrimer(
            ChatTimelinePrimer.Hooks(
                registry = hooks.registry,
                store = hooks.store,
                isConversationActive = hooks.isConversationActive,
                warmPreviews = hooks.warmPreviews,
                loadContacts = hooks.loadContacts,
                indexPage = hooks.indexPage,
                envInputs = hooks.envInputs,
                unknownLabel = hooks.unknownLabel,
            ),
        ).prime(conversation)
    }

    companion object {
        /** Coalescing window; a sync catch-up burst rides one refresh. iOS uses 250 ms. */
        const val DEFAULT_DEBOUNCE_MS: Long = 250L
    }
}
