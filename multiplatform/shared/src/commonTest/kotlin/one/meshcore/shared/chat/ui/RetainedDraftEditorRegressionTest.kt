package one.meshcore.shared.chat.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.BackupImportResult
import one.meshcore.shared.backup.clearAffectedChannelDrafts
import one.meshcore.shared.chat.ChatConversationID
import one.meshcore.shared.chat.ChatDraftEditors
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.chat.DraftStore
import one.meshcore.shared.chat.FakeChatThreadStore
import one.meshcore.shared.chat.makeThreadViewModel
import one.meshcore.shared.notification.ActiveConversationState
import one.meshcore.shared.notification.NotificationCoordinator
import one.meshcore.shared.notification.RecordingPoster
import one.meshcore.shared.notification.consumeQuickReplyDraft
import one.meshcore.shared.notification.notificationKey
import one.meshcore.shared.notification.saveQuickReplyDraft
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedDraftEditorRegressionTest {
    private val conversation = ChatConversationID.channel(ChatFixtures.RADIO, CHANNEL)

    @Test
    fun `clean old presentation teardown preserves the newer presentation edit`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        drafts.setDraft(SAVED, conversation)
        val old = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        old.restoreDraft()
        assertEquals(SAVED, old.state.value.text)
        val replacement = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        replacement.restoreDraft()
        replacement.setText(LIVE)
        replacement.flushDraft()
        assertEquals(LIVE, drafts.draft(conversation))
        val (thread, _) = makeThreadViewModel(
            store = FakeChatThreadStore(),
            conversationID = conversation,
            viewModelScope = backgroundScope,
        )

        disposeChatThread(backgroundScope, old, thread)
        runCurrent()

        assertEquals(LIVE, replacement.state.value.text)
        assertEquals(LIVE, drafts.draft(conversation))
    }

    @Test
    fun `empty pending reply preserves current unsent text`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        drafts.setDraft(SAVED, conversation)
        val composer = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        composer.setText(LIVE)

        composer.restoreDraft(quickReply = "")

        assertEquals(LIVE, composer.state.value.text)
    }

    @Test
    fun `backup affected slot clears live text and prevents stale flush resurrection`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        drafts.setDraft(SAVED, conversation)
        val unaffected = ChatConversationID.channel(ChatFixtures.RADIO, OTHER_CHANNEL)
        drafts.setDraft(OTHER, unaffected)
        val composer = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        composer.restoreDraft()
        composer.setText(LIVE)

        BackupImportResult(channelSlotsAffected = mapOf(ChatFixtures.RADIO to setOf(CHANNEL)))
            .clearAffectedChannelDrafts(owners)
        assertNull(drafts.draft(conversation))
        composer.flushDraft()

        assertNull(drafts.draft(conversation))
        assertEquals("", composer.state.value.text)
        assertEquals(OTHER, drafts.draft(unaffected))
    }

    @Test
    fun `nonempty pending reply replaces current unsent text`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val composer = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        composer.setText(LIVE)

        composer.restoreDraft(quickReply = REPLY)

        assertEquals(REPLY, composer.state.value.text)
    }

    @Test
    fun `absent pending reply preserves current unsent text`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        drafts.setDraft(SAVED, conversation)
        val composer = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        composer.setText(LIVE)

        composer.restoreDraft()

        assertEquals(LIVE, composer.state.value.text)
    }

    @Test
    fun `clean presentation teardown preserves its saved draft`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        drafts.setDraft(SAVED, conversation)
        val composer = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        composer.restoreDraft()
        val (thread, _) = makeThreadViewModel(
            store = FakeChatThreadStore(),
            conversationID = conversation,
            viewModelScope = backgroundScope,
        )

        disposeChatThread(backgroundScope, composer, thread)
        runCurrent()

        assertEquals(SAVED, drafts.draft(conversation))
    }

    @Test
    fun `closed presentation leaves parked reply for the live presentation`() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val owners = ChatDraftEditors(drafts, backgroundScope)
        val coordinator = NotificationCoordinator(
            poster = RecordingPoster(),
            activeConversation = ActiveConversationState(),
            scope = backgroundScope,
            now = { 0.0 },
        )
        val environment = ChatThreadEnvironment(
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
        val old = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        old.close()
        coordinator.saveQuickReplyDraft(conversation.notificationKey(), REPLY)

        environment.restoreComposerDraft(old, conversation)
        val live = ComposerViewModel(backgroundScope, owners.editor(conversation), MAX_BYTES) {}
        environment.restoreComposerDraft(live, conversation)

        assertEquals(REPLY, live.state.value.text)
        assertNull(coordinator.consumeQuickReplyDraft(conversation.notificationKey()))
    }

    private companion object {
        const val CHANNEL = 3
        const val OTHER_CHANNEL = 4
        const val MAX_BYTES = 150
        const val SAVED = "saved draft"
        const val LIVE = "newer unsent text"
        const val REPLY = "notification reply"
        const val OTHER = "other slot draft"
    }
}
