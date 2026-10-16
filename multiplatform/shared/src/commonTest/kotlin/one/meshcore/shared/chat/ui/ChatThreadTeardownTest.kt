package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.FakeChatThreadStore
import one.meshcore.shared.chat.loadInitial
import one.meshcore.shared.chat.makeThreadViewModel
import one.meshcore.shared.notification.ActiveConversationState
import one.meshcore.shared.notification.NotificationActiveConversationSink
import one.meshcore.shared.notification.NotificationCoordinator
import one.meshcore.shared.notification.RecordingPoster
import one.meshcore.shared.notification.clearActiveChannel
import one.meshcore.shared.notification.clearActiveContact
import one.meshcore.shared.notification.setActiveChannel
import one.meshcore.shared.notification.setActiveContact
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The chat thread's teardown, driven under the condition Compose actually creates: the composable's own
 * coroutine scope is cancelled in the same apply pass that runs `onDispose`. Work launched on that scope
 * never runs, so a claim released there is never released — that conversation then raises no banner and
 * bumps no unread for as long as the app stays foregrounded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadTeardownTest {
    private val conversation = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT)

    @Test
    fun `leaving a thread releases the claim and persists the draft after the screen scope dies`() = runTest {
        val activeConversation = ActiveConversationState()
        val coordinator = coordinator(activeConversation)
        val appScope = scope()
        val screenScope = scope()
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(
            scope = screenScope,
            draftEditor = owners.editor(conversation),
            maxBytes = MAX_BYTES,
            onSend = {},
        )
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(),
            conversationID = conversation,
            activeConversation = NotificationActiveConversationSink(coordinator, appScope),
            viewModelScope = screenScope,
        )

        viewModel.loadInitial()
        testScheduler.advanceUntilIdle()
        assertEquals(ChatFixtures.CONTACT, activeConversation.activeContactID())

        // Typed inside the 500 ms draft debounce: the teardown flush is what persists it.
        composer.setText(TYPED)

        // Compose runs onDispose and cancels the composable's scope in the same apply pass.
        disposeChatThread(teardownScope = appScope, composer = composer, viewModel = viewModel)
        screenScope.cancel()
        testScheduler.advanceUntilIdle()

        assertNull(activeConversation.activeContactID())
        assertEquals(TYPED, drafts.draft(conversation))
        appScope.cancel()
    }

    @Test
    fun `clearing the composer before teardown does not re-save a draft on a vacated slot`() = runTest {
        val appScope = scope()
        val screenScope = scope()
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(
            scope = screenScope,
            draftEditor = owners.editor(conversation),
            maxBytes = MAX_BYTES,
            onSend = {},
        )
        val (viewModel, _) = makeThreadViewModel(
            store = FakeChatThreadStore(),
            conversationID = conversation,
            viewModelScope = screenScope,
        )

        composer.setText(TYPED)
        composer.setText("")
        disposeChatThread(teardownScope = appScope, composer = composer, viewModel = viewModel)
        screenScope.cancel()
        testScheduler.advanceUntilIdle()

        assertNull(drafts.draft(conversation))
        appScope.cancel()
    }

    @Test
    fun `a background pulse flushes the composer draft inside the debounce window`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(
            scope = backgroundScope,
            draftEditor = owners.editor(conversation),
            maxBytes = MAX_BYTES,
            onSend = {},
        )
        composer.setText(TYPED)
        composer.flushDraft()
        testScheduler.advanceUntilIdle()
        assertEquals(TYPED, drafts.draft(conversation))
    }

    @Test
    fun `a stale DM teardown does not un-claim the conversation that just opened`() = runTest {
        val activeConversation = ActiveConversationState()
        val coordinator = coordinator(activeConversation)
        val other = ChatFixtures.freshUuid()

        coordinator.setActiveContact(ChatFixtures.CONTACT)
        coordinator.setActiveContact(other)
        coordinator.clearActiveContact(ChatFixtures.CONTACT)

        assertEquals(other, activeConversation.activeContactID())

        coordinator.clearActiveContact(other)
        assertNull(activeConversation.activeContactID())
    }

    @Test
    fun `a stale channel teardown does not un-claim the conversation that just opened`() = runTest {
        val activeConversation = ActiveConversationState()
        val coordinator = coordinator(activeConversation)

        coordinator.setActiveChannel(ChatFixtures.RADIO, CHANNEL_LEFT)
        coordinator.setActiveContact(ChatFixtures.CONTACT)
        coordinator.clearActiveChannel(ChatFixtures.RADIO, CHANNEL_LEFT)

        assertEquals(ChatFixtures.CONTACT, activeConversation.activeContactID())
    }

    @Test
    fun `a channel teardown releases the slot it still owns`() = runTest {
        val activeConversation = ActiveConversationState()
        val coordinator = coordinator(activeConversation)

        coordinator.setActiveChannel(ChatFixtures.RADIO, CHANNEL_LEFT)
        coordinator.clearActiveChannel(ChatFixtures.RADIO, CHANNEL_LEFT)

        assertNull(activeConversation.activeChannel())
    }

    private fun TestScope.coordinator(state: ActiveConversationState) = NotificationCoordinator(
        poster = RecordingPoster(),
        activeConversation = state,
        scope = backgroundScope,
        now = { 0.0 },
    )

    private fun TestScope.scope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    private companion object {
        const val TYPED = "holding at the north trailhead"
        const val MAX_BYTES = 150
        const val CHANNEL_LEFT = 3
    }
}
