package one.meshcore.shared.chat.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.notification.ActiveConversationState
import one.meshcore.shared.notification.ConversationKey
import one.meshcore.shared.notification.NotificationCoordinator
import one.meshcore.shared.notification.RecordingPoster
import one.meshcore.shared.notification.consumeQuickReplyDraft
import one.meshcore.shared.notification.notificationKey
import one.meshcore.shared.notification.saveQuickReplyDraft
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The parked-quick-reply half of the thread's open-time restore: text a failed notification reply parked
 * must reach the composer of the conversation it was typed for, and must reach it exactly once. Runs the
 * production chain — the coordinator's draft store, the environment provider the container supplies, and
 * [restoreComposerDraft] — so an unwired provider fails here rather than silently losing the reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadDraftRestoreTest {
    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val contact = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    private val conversation = ChatConversationID.dm(radio, contact)

    @Test
    fun `a parked quick reply lands in the composer of the thread it was typed for`() = runTest {
        val coordinator = coordinator()
        coordinator.saveQuickReplyDraft(ConversationKey.direct(contact), PARKED)
        val environment = environment(coordinator)
        val composer = composer(ChatDraftEditors(DraftStore.create(InMemoryPreferencesStore()), backgroundScope))

        environment.restoreComposerDraft(composer, conversation)

        assertEquals(PARKED, composer.state.value.text)
    }

    @Test
    fun `reopening the thread does not restore the parked reply a second time`() = runTest {
        val coordinator = coordinator()
        coordinator.saveQuickReplyDraft(ConversationKey.direct(contact), PARKED)
        val environment = environment(coordinator)
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(draftStore, backgroundScope)

        val first = composer(owners)
        environment.restoreComposerDraft(first, conversation)
        val reopened = composer(owners)
        environment.restoreComposerDraft(reopened, conversation)

        assertEquals(PARKED, first.state.value.text)
        assertEquals(PARKED, reopened.state.value.text)
        assertEquals(null, coordinator.consumeQuickReplyDraft(conversation.notificationKey()))
    }

    @Test
    fun `with nothing parked the persisted disk draft still restores`() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(draftStore, backgroundScope)
        draftStore.setDraft(DISK, conversation)
        val composer = composer(owners)

        environment(coordinator()).restoreComposerDraft(composer, conversation)

        assertEquals(DISK, composer.state.value.text)
    }

    @Test
    fun `a reply parked for another conversation stays out of this composer`() = runTest {
        val coordinator = coordinator()
        val other = Uuid.normalized("CCCCCCCC-CCCC-CCCC-CCCC-CCCCCCCCCCCC")
        coordinator.saveQuickReplyDraft(ConversationKey.direct(other), PARKED)
        val composer = composer(ChatDraftEditors(DraftStore.create(InMemoryPreferencesStore()), backgroundScope))

        environment(coordinator).restoreComposerDraft(composer, conversation)

        assertEquals("", composer.state.value.text)
    }

    private fun TestScope.coordinator() = NotificationCoordinator(
        poster = RecordingPoster(),
        activeConversation = ActiveConversationState(),
        scope = this,
        now = { 0.0 },
    )

    private fun TestScope.composer(owners: ChatDraftEditors) = ComposerViewModel(
        scope = backgroundScope,
        draftEditor = owners.editor(conversation),
        maxBytes = MAX_BYTES,
        onSend = {},
    )

    /** The disconnected environment plus the one provider under test, wired as the container wires it. */
    private fun TestScope.environment(coordinator: NotificationCoordinator) = ChatThreadEnvironment(
        graph = ChatThreadGraphInputs(
            registry = { null },
            store = { null },
            sendQueue = { null },
            messageService = { null },
            draftEditors = { null },
            teardownScope = { backgroundScope },
            currentContact = { null },
            unreadCount = { 0 },
            localNodeName = { null },
            incomingEvents = { emptyFlow() },
            statusEvents = { emptyFlow() },
        ),
        lifecycle = ChatThreadLifecycleInputs(
            consumeQuickReplyDraft = { id, acceptsDraft ->
                coordinator.consumeQuickReplyDraft(id.notificationKey(), acceptsDraft)
            },
        ),
    )

    private companion object {
        const val PARKED = "on my way"
        const val DISK = "half-typed"
        const val MAX_BYTES = 150
    }
}
