package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the composer view model's stateful behavior under the test scheduler's virtual clock: the
 * send flow (trim, clear, fire), the one-second post-send cooldown, the debounced-and-flushed draft
 * persistence, and the restore-priority order. Mirrors the iOS `ChatInputBar` send/cooldown plus the
 * `ChatViewModel` draft plumbing and `DraftStore` restore guard.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerViewModelTest {
    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val contact = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")
    private val id = ChatConversationID.dm(radio, contact)

    private fun TestScope.composer(draftStore: DraftStore, maxBytes: Int = MAX, onSend: (String) -> Unit = {}) =
        ComposerViewModel(backgroundScope, ChatDraftEditors(draftStore, backgroundScope).editor(id), maxBytes, onSend)

    @Test
    fun requestFocusIncrementsAfterTheSheetDismissalDelay() = runTest {
        val vm = composer(DraftStore.create(InMemoryPreferencesStore()))
        assertEquals(0, vm.state.value.focusRequest)
        vm.requestFocus()
        assertEquals(0, vm.state.value.focusRequest)
        advanceTimeBy(ChatConstants.MESSAGE_ACTIONS_DISMISSAL_MS)
        runCurrent()
        assertEquals(1, vm.state.value.focusRequest)
    }

    @Test
    fun requestFocusNowIncrementsImmediately() = runTest {
        val vm = composer(DraftStore.create(InMemoryPreferencesStore()))
        vm.requestFocusNow()
        assertEquals(1, vm.state.value.focusRequest)
    }

    @Test
    fun closeCancelsPresentationWorkAndRetainsTheEditorDraft() = runTest {
        val drafts = DraftStore.create(InMemoryPreferencesStore())
        val sent = mutableListOf<String>()
        val vm = composer(drafts, onSend = sent::add)
        vm.setText("pending")
        vm.requestFocus()

        vm.close()
        advanceTimeBy(ComposerViewModel.DRAFT_SAVE_DEBOUNCE_MS + 1)
        runCurrent()
        vm.setText("late")
        vm.requestFocusNow()

        assertFalse(vm.send(isReady = true))
        assertEquals("pending", vm.state.value.text)
        assertEquals(0, vm.state.value.focusRequest)
        assertEquals("pending", drafts.draft(id))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun keepingTheChatComposerByteBudgetDoesNotRadio() = runTest {
        var wireCalls = 0
        val prefs = InMemoryPreferencesStore()
        val draftStore = DraftStore.create(prefs)
        val vm = composer(draftStore) { wireCalls += 1 }
        assertEquals(MAX, vm.state.value.maxBytes)
        assertEquals("", vm.state.value.text)
        assertEquals(0, wireCalls)
        assertTrue(prefs.snapshot().isEmpty())

        vm.updateMaxBytes(MAX)
        advanceUntilIdle()

        assertEquals(MAX, vm.state.value.maxBytes)
        assertEquals("", vm.state.value.text)
        assertEquals(0, wireCalls)
        assertFalse(vm.state.value.isCoolingDown)
        assertEquals(0, vm.state.value.focusRequest)
        assertNull(draftStore.draft(id))
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun keepingAnEmptyChatComposerDoesNotRadio() = runTest {
        var wireCalls = 0
        val prefs = InMemoryPreferencesStore()
        val draftStore = DraftStore.create(prefs)
        val vm = composer(draftStore) { wireCalls += 1 }
        assertEquals("", vm.state.value.text)
        assertFalse(vm.state.value.isCoolingDown)
        assertEquals(0, vm.state.value.focusRequest)
        assertEquals(0, wireCalls)
        assertTrue(prefs.snapshot().isEmpty())

        vm.setText("")
        advanceUntilIdle()

        assertEquals(0, wireCalls)
        assertEquals("", vm.state.value.text)
        assertFalse(vm.state.value.isCoolingDown)
        assertEquals(0, vm.state.value.focusRequest)
        assertNull(draftStore.draft(id))
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun focusingTheComposerDoesNotSend() = runTest {
        var sends = 0
        val prefs = InMemoryPreferencesStore()
        val vm = composer(DraftStore.create(prefs), onSend = { sends += 1 })
        assertEquals(0, vm.state.value.focusRequest)
        assertEquals("", vm.state.value.text)

        vm.requestFocusNow()
        runCurrent()

        assertEquals(1, vm.state.value.focusRequest)
        assertEquals(0, sends)
        assertEquals("", vm.state.value.text)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun insertSharedAppendsToLiveTextAndFocuses() = runTest {
        val vm = composer(DraftStore.create(InMemoryPreferencesStore()))
        vm.setText("hello")
        vm.setText("hello there")
        vm.insertShared("https://maps")
        assertEquals("hello there https://maps", vm.state.value.text)
        assertEquals(1, vm.state.value.focusRequest)
    }

    @Test
    fun insertingSharedTextIntoAnEmptyComposerDoesNotSend() = runTest {
        var sends = 0
        val prefs = InMemoryPreferencesStore()
        val vm = composer(DraftStore.create(prefs), onSend = { sends += 1 })
        assertEquals("", vm.state.value.text)
        assertEquals(0, vm.state.value.focusRequest)

        vm.insertShared("https://maps")
        runCurrent()

        assertEquals("https://maps", vm.state.value.text)
        assertEquals(1, vm.state.value.focusRequest)
        assertFalse(vm.state.value.isOverLimit)
        assertEquals(0, sends)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun sendFiresTrimmedTextAndClearsTheField() = runTest {
        val sent = mutableListOf<String>()
        val vm = composer(DraftStore.create(InMemoryPreferencesStore())) { sent.add(it) }

        vm.setText("  hello world  ")
        assertTrue(vm.send(isReady = true))

        assertEquals(listOf("hello world"), sent)
        assertEquals("", vm.state.value.text)
    }

    @Test
    fun sendIsGatedByReadyEmptyAndOverLimit() = runTest {
        val sent = mutableListOf<String>()
        val vm = composer(DraftStore.create(InMemoryPreferencesStore()), maxBytes = 4) { sent.add(it) }

        vm.setText("hi")
        assertFalse(vm.send(isReady = false)) // disconnected

        vm.setText("   ")
        assertFalse(vm.send(isReady = true)) // empty after trim

        vm.setText("😀😀") // 8 bytes over the 4-byte budget
        assertFalse(vm.send(isReady = true))

        assertTrue(sent.isEmpty())
    }

    @Test
    fun updateMaxBytesTightensTheLiveBudget() = runTest {
        val vm = composer(DraftStore.create(InMemoryPreferencesStore()), maxBytes = 20)
        vm.setText("hello")
        assertFalse(vm.state.value.isOverLimit)

        vm.updateMaxBytes(3)

        assertEquals(3, vm.state.value.maxBytes)
        assertTrue(vm.state.value.isOverLimit)
    }

    @Test
    fun anUnchangedComposerByteBudgetDoesNotSend() = runTest {
        var sends = 0
        val prefs = InMemoryPreferencesStore()
        val vm = composer(DraftStore.create(prefs), maxBytes = 20, onSend = { sends += 1 })
        assertEquals(20, vm.state.value.maxBytes)
        assertEquals("", vm.state.value.text)

        vm.updateMaxBytes(20)
        runCurrent()

        assertEquals(20, vm.state.value.maxBytes)
        assertEquals(0, sends)
        assertEquals("", vm.state.value.text)
        assertFalse(vm.state.value.isOverLimit)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun shrinkingAnEmptyComposerBudgetDoesNotSend() = runTest {
        var sends = 0
        val prefs = InMemoryPreferencesStore()
        val vm = composer(DraftStore.create(prefs), maxBytes = MAX, onSend = { sends += 1 })
        assertEquals(MAX, vm.state.value.maxBytes)
        assertEquals("", vm.state.value.text)
        assertFalse(vm.state.value.isOverLimit)

        vm.updateMaxBytes(3)
        runCurrent()

        assertEquals(3, vm.state.value.maxBytes)
        assertEquals(0, sends)
        assertEquals("", vm.state.value.text)
        assertFalse(vm.state.value.isOverLimit)
        assertTrue(prefs.snapshot().isEmpty())
    }

    @Test
    fun typingInTheChatComposerDoesNotSend() = runTest {
        var sends = 0
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        val vm = composer(draftStore, onSend = { sends += 1 })

        vm.setText("draft text")
        advanceTimeBy(ComposerViewModel.DRAFT_SAVE_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(0, sends)
        assertEquals("draft text", vm.state.value.text)
        assertEquals("draft text", draftStore.draft(id))
    }

    @Test
    fun cooldownBlocksSendForOneSecond() = runTest {
        val sent = mutableListOf<String>()
        val vm = composer(DraftStore.create(InMemoryPreferencesStore())) { sent.add(it) }

        vm.setText("first")
        assertTrue(vm.send(isReady = true))
        assertTrue(vm.state.value.isCoolingDown)

        vm.setText("second")
        assertFalse(vm.send(isReady = true)) // still cooling down

        advanceTimeBy(ComposerViewModel.SEND_COOLDOWN_MS + 1)
        runCurrent()
        assertFalse(vm.state.value.isCoolingDown)
        assertTrue(vm.state.value.canSend(isReady = true))
        assertTrue(vm.send(isReady = true))

        assertEquals(listOf("first", "second"), sent)
    }

    @Test
    fun draftPersistsOnlyAfterTheDebounceWindow() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        val vm = composer(draftStore)

        vm.setText("draft text")
        assertNull(draftStore.draft(id))

        advanceTimeBy(ComposerViewModel.DRAFT_SAVE_DEBOUNCE_MS - 1)
        runCurrent()
        assertNull(draftStore.draft(id))

        advanceTimeBy(2)
        runCurrent()
        assertEquals("draft text", draftStore.draft(id))
    }

    @Test
    fun aSecondEditCancelsThePriorPendingDraftWrite() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        val vm = composer(draftStore)

        vm.setText("first")
        advanceTimeBy(200)
        runCurrent()
        vm.setText("second")

        // Past the first edit's 500ms deadline but before the second's: the first write was cancelled.
        advanceTimeBy(400)
        runCurrent()
        assertNull(draftStore.draft(id))

        advanceTimeBy(200)
        runCurrent()
        assertEquals("second", draftStore.draft(id))
    }

    @Test
    fun flushPersistsTheCurrentDraftImmediately() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        val vm = composer(draftStore)

        vm.setText("unsaved")
        vm.flushDraft()

        assertEquals("unsaved", draftStore.draft(id))
    }

    @Test
    fun flushingAChatDraftDoesNotRadio() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        var sends = 0
        val vm = composer(draftStore, onSend = { sends += 1 })

        vm.setText("unsaved")
        vm.flushDraft()
        runCurrent()

        assertEquals(0, sends)
        assertEquals("unsaved", vm.state.value.text)
        assertEquals("unsaved", draftStore.draft(id))
        assertEquals(false, vm.state.value.isCoolingDown)
    }

    @Test
    fun sendClearsThePersistedDraft() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        val vm = composer(draftStore)

        vm.setText("hello")
        advanceTimeBy(ComposerViewModel.DRAFT_SAVE_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals("hello", draftStore.draft(id))

        vm.send(isReady = true)
        runCurrent()
        advanceUntilIdle()
        assertNull(draftStore.draft(id))
    }

    @Test
    fun restoreQuickReplyWinsOverTheDiskDraft() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        draftStore.setDraft("disk", id)
        val vm = composer(draftStore)

        vm.restoreDraft(quickReply = "reply")

        assertEquals("reply", vm.state.value.text)
    }

    @Test
    fun restoringAQuickReplyDoesNotRadio() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        draftStore.setDraft("disk", id)
        var sends = 0
        val vm = composer(draftStore, onSend = { sends += 1 })

        vm.restoreDraft(quickReply = "On my way")
        runCurrent()

        assertEquals(0, sends)
        assertEquals("On my way", vm.state.value.text)
        assertEquals(false, vm.state.value.isCoolingDown)
        assertEquals("disk", draftStore.draft(id))
    }

    @Test
    fun restoreAppliesTheDiskDraftWhenTheFieldIsEmpty() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        draftStore.setDraft("disk", id)
        val vm = composer(draftStore)

        vm.restoreDraft(quickReply = null)

        assertEquals("disk", vm.state.value.text)
    }

    @Test
    fun restoreLeavesInProgressTextAlone() = runTest {
        val draftStore = DraftStore.create(InMemoryPreferencesStore())
        draftStore.setDraft("disk", id)
        val vm = composer(draftStore)

        vm.setText("typing")
        vm.restoreDraft(quickReply = null)

        assertEquals("typing", vm.state.value.text)
    }

    private companion object {
        const val MAX = 150
    }
}
