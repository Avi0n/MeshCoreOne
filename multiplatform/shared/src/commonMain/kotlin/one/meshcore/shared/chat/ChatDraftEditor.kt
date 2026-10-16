package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** App-owned text intent for one conversation. All entry points belong to the host UI dispatcher. */
class ChatDraftEditor internal constructor(
    val conversationID: ChatConversationID,
    private val store: DraftStore,
    private val scope: CoroutineScope,
    private val clearBoundary: () -> Deferred<Unit>?,
) {
    private val mutableText = MutableStateFlow("")
    val text: StateFlow<String> = mutableText.asStateFlow()
    private val saveMutex = Mutex()
    private var revision = 0L
    private var isDirty = false
    private var isInitialized = false
    private var saveJob: Job? = null
    private var initialization: Deferred<Unit>? = null

    var isRetired = false
        private set

    var acceptsInput = true
        private set

    init {
        val seedRevision = revision
        initialization = scope.async(start = CoroutineStart.LAZY) {
            clearBoundary()?.await()
            val saved = store.draft(conversationID)
            if (!isRetired && revision == seedRevision) mutableText.value = saved.orEmpty()
            isInitialized = true
        }.also { it.start() }
    }

    fun setText(value: String) {
        if (!acceptsInput || (isInitialized && value == mutableText.value)) return
        revision += 1
        mutableText.value = value
        isDirty = true
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveCurrent()
        }
    }

    suspend fun awaitInitialized() {
        if (isRetired) return
        initialization?.await()
        clearBoundary()?.await()
    }

    suspend fun flush() {
        saveJob?.cancel()
        saveJob = null
        saveCurrent()
    }

    fun requestFlush(): Job = scope.launch { flush() }

    internal fun reset() {
        if (isRetired) return
        invalidatePendingWork()
        mutableText.value = ""
        isDirty = false
        isInitialized = true
    }

    internal fun stopAcceptingInput() {
        acceptsInput = false
        saveJob?.cancel()
        saveJob = null
    }

    internal fun retire() {
        if (isRetired) return
        isRetired = true
        acceptsInput = false
        invalidatePendingWork()
    }

    internal suspend fun awaitAdmittedWrite() {
        saveMutex.withLock { }
    }

    private fun invalidatePendingWork() {
        revision += 1
        saveJob?.cancel()
        saveJob = null
        initialization?.cancel()
        initialization = null
    }

    private suspend fun saveCurrent() {
        while (!isRetired && isDirty) {
            val boundary = clearBoundary()
            boundary?.await()
            val settled = saveMutex.withLock {
                // A clear drains admitted writes; never wait for that clear while holding this mutex.
                if (clearBoundary() !== boundary) return@withLock false
                if (isRetired || !isDirty) return@withLock true
                val savedRevision = revision
                store.setDraft(mutableText.value, conversationID)
                if (revision == savedRevision) isDirty = false
                true
            }
            if (settled) return
        }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 500L
    }
}
