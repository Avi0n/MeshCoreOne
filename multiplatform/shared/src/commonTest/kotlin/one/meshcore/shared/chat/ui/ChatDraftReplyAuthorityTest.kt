package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.notification.ActiveConversationState
import one.meshcore.shared.notification.NotificationCoordinator
import one.meshcore.shared.notification.RecordingPoster
import one.meshcore.shared.notification.consumeQuickReplyDraft
import one.meshcore.shared.notification.notificationKey
import one.meshcore.shared.notification.saveQuickReplyDraft
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatDraftReplyAuthorityTest {
    @Test
    fun `screen closed during suspended consume leaves the parked reply`() = runTest {
        replySurvives(Revocation.SCREEN)
    }

    @Test
    fun `slot retired during suspended consume leaves the parked reply`() = runTest {
        replySurvives(Revocation.SLOT)
    }

    @Test
    fun `shutdown during suspended consume leaves the parked reply`() = runTest {
        replySurvives(Revocation.SHUTDOWN)
    }

    private suspend fun TestScope.replySurvives(revocation: Revocation) {
        val store = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(store, backgroundScope)
        val coordinator = NotificationCoordinator(
            poster = RecordingPoster(),
            activeConversation = ActiveConversationState(),
            scope = backgroundScope,
            now = { 0.0 },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val environment = ChatThreadEnvironment(
            graph = ChatThreadGraphInputs(
                registry = { null },
                store = { null },
                sendQueue = { null },
                messageService = { null },
                draftEditors = { owners },
                teardownScope = { backgroundScope },
                currentContact = { null },
                unreadCount = { 0 },
                localNodeName = { null },
                incomingEvents = { emptyFlow() },
                statusEvents = { emptyFlow() },
            ),
            lifecycle = ChatThreadLifecycleInputs(
                consumeQuickReplyDraft = { id, acceptsDraft ->
                    calls += 1
                    entered.complete(Unit)
                    release.await()
                    coordinator.consumeQuickReplyDraft(id.notificationKey(), acceptsDraft)
                },
            ),
        )
        val old = ComposerViewModel(backgroundScope, owners.editor(ID), MAX_BYTES) {}
        old.setText(LIVE)
        coordinator.saveQuickReplyDraft(ID.notificationKey(), REPLY)
        val restoring = async { environment.restoreComposerDraft(old, ID) }
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            assertFalse(restoring.isCompleted)
            val replacementOwner = when (revocation) {
                Revocation.SCREEN -> owners.also { old.close() }
                Revocation.SLOT -> owners.also { it.retireChannelDrafts(ChatFixtures.RADIO, setOf(CHANNEL)) }
                Revocation.SHUTDOWN -> {
                    owners.shutdown()
                    ChatDraftEditors(store, backgroundScope)
                }
            }
            assertFalse(old.acceptsDraft)
            release.complete(Unit)
            restoring.await()
            val live = ComposerViewModel(backgroundScope, replacementOwner.editor(ID), MAX_BYTES) {}
            environment.restoreComposerDraft(live, ID)

            assertEquals(REPLY, live.state.value.text)
            assertEquals(2, calls)
            assertNull(coordinator.consumeQuickReplyDraft(ID.notificationKey()))
        } finally {
            release.complete(Unit)
            restoring.cancel()
        }
    }

    private enum class Revocation {
        SCREEN,
        SLOT,
        SHUTDOWN,
    }

    private companion object {
        const val CHANNEL = 3
        const val MAX_BYTES = 150
        val ID = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL)
        const val LIVE = "current unsent text"
        const val REPLY = "parked notification reply"
    }
}
