package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.lifecycle.OwnedJobs

/** Screen-owned input presentation and send gating over an app-owned conversation editor. */
class ComposerViewModel(
    scope: CoroutineScope,
    private val draftEditor: ChatDraftEditor,
    maxBytes: Int,
    private val onSend: (String) -> Unit,
) {
    private val _state = MutableStateFlow(ComposerState(text = draftEditor.text.value, maxBytes = maxBytes))
    private val jobs = OwnedJobs(scope)
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    internal val acceptsDraft: Boolean
        get() = !jobs.isClosed && draftEditor.acceptsInput

    internal val currentText: String
        get() = draftEditor.text.value

    init {
        jobs.launch {
            draftEditor.text.collect { text -> _state.update { it.copy(text = text) } }
        }
    }

    fun updateMaxBytes(maxBytes: Int) {
        if (jobs.isClosed || _state.value.maxBytes == maxBytes) return
        _state.update { it.copy(maxBytes = maxBytes) }
    }

    fun setText(text: String) {
        if (!acceptsDraft) return
        draftEditor.setText(text)
        synchronizeText()
    }

    fun requestFocus() {
        jobs.launch {
            delay(ChatConstants.MESSAGE_ACTIONS_DISMISSAL_MS)
            bumpFocus()
        }
    }

    fun requestFocusNow() {
        if (jobs.isClosed) return
        bumpFocus()
    }

    private fun bumpFocus() {
        _state.update { it.copy(focusRequest = it.focusRequest + 1) }
    }

    fun send(isReady: Boolean): Boolean {
        if (!acceptsDraft) return false
        val current = _state.value.copy(text = currentText)
        if (!current.canSend(isReady)) return false
        val trimmed = current.text.trim()
        draftEditor.setText("")
        _state.update { it.copy(text = currentText, isCoolingDown = true) }
        draftEditor.requestFlush()
        onSend(trimmed)
        jobs.launch {
            delay(SEND_COOLDOWN_MS)
            _state.update { it.copy(isCoolingDown = false) }
        }
        return true
    }

    /** Empty parked replies are consumed without changing the retained editor. */
    suspend fun restoreDraft(quickReply: String? = null) {
        if (!acceptsDraft) return
        if (!quickReply.isNullOrEmpty()) {
            setText(quickReply)
            return
        }
        if (currentText.isEmpty()) draftEditor.awaitInitialized()
        if (acceptsDraft) synchronizeText()
    }

    suspend fun flushDraft() = draftEditor.flush()

    internal fun close() = jobs.close()

    private fun synchronizeText() {
        _state.update { it.copy(text = currentText) }
    }

    companion object {
        const val SEND_COOLDOWN_MS = 1_000L
        const val DRAFT_SAVE_DEBOUNCE_MS = ChatDraftEditor.SAVE_DEBOUNCE_MS
    }
}

/** Reads authoritative text so a pending presentation update cannot discard another view's edit. */
fun ComposerViewModel.insertShared(shared: String) {
    if (!acceptsDraft) return
    setText(ChatShareLogic.appendSharedText(currentText, shared))
    requestFocusNow()
}
