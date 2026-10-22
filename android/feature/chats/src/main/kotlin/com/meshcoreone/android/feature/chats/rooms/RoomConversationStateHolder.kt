// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/ViewModels/RoomConversationViewModelOrderingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RoomMessageRow(
    val message: RoomMessageDTO,
    val showTimestamp: Boolean,
    val showSenderName: Boolean,
    val showAvatar: Boolean,
)

data class RoomConversationState(
    val session: RemoteNodeSessionDTO,
    val messages: List<RoomMessageDTO> = emptyList(),
    val rows: List<RoomMessageRow> = emptyList(),
    val draft: String = "",
    val isLoading: Boolean = false,
    val hasLoadedOnce: Boolean = false,
    val isSending: Boolean = false,
    val isAuthenticating: Boolean = false,
    val authenticationContact: ContactDTO? = null,
    val authenticationLookupComplete: Boolean = false,
    val failure: Throwable? = null,
)

class RoomConversationStateHolder(
    session: RemoteNodeSessionDTO,
    private val dependencies: RoomConversationDependencies,
    private val scope: CoroutineScope,
    private val awaitReloadDebounce: suspend () -> Unit = { delay(RELOAD_DEBOUNCE) },
) {
    private val mutableState = MutableStateFlow(RoomConversationState(session))
    val state: StateFlow<RoomConversationState> = mutableState

    private val operation = Mutex()
    private var eventsJob: Job? = null
    private var reloadJob: Job? = null
    private var generation = 0L

    fun start() {
        if (eventsJob != null) return
        eventsJob = scope.launch {
            dependencies.events.collect { event ->
                if (event.sessionId != mutableState.value.session.id) return@collect
                when (event) {
                    is RoomConversationEvent.MessageReceived -> {
                        appendIfNew(event.message)
                        scheduleReload()
                    }
                    is RoomConversationEvent.MessageChanged -> {
                        if (mutableState.value.messages.any { it.id == event.messageId }) scheduleReload()
                    }
                    is RoomConversationEvent.ConnectionRecovered -> mutableState.update {
                        it.copy(session = event.session, failure = null)
                    }
                }
            }
        }
    }

    suspend fun load() {
        val loadGeneration = ++generation
        mutableState.update { it.copy(isLoading = true, failure = null) }
        try {
            operation.withLock {
                val session = mutableState.value.session
                val messages = dependencies.data.fetchMessages(session)
                    .distinctBy(RoomMessageDTO::id)
                    .sortedWith(compareBy<RoomMessageDTO> { it.timestamp }.thenBy { it.createdAt }.thenBy { it.id })
                if (loadGeneration != generation) return@withLock
                mutableState.update {
                    it.copy(messages = messages, rows = rows(messages), isLoading = false, hasLoadedOnce = true)
                }
                dependencies.data.markAsRead(session)
                dependencies.data.markFailedSendsSeen(session)
                dependencies.removeDeliveredNotifications(session)
                dependencies.updateBadgeCount()
                dependencies.conversationsChanged()
            }
        } catch (cancelled: CancellationException) {
            if (loadGeneration == generation) mutableState.update { it.copy(isLoading = false) }
            throw cancelled
        } catch (failure: Exception) {
            if (loadGeneration == generation) {
                mutableState.update { it.copy(isLoading = false, hasLoadedOnce = true, failure = failure) }
            }
            dependencies.diagnostics.report("load room", failure)
        }
    }

    suspend fun refreshSession() {
        try {
            dependencies.data.refreshSession(mutableState.value.session)?.let { refreshed ->
                mutableState.update { it.copy(session = refreshed) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            dependencies.diagnostics.report("refresh room session", failure)
        }
    }

    suspend fun prepareAuthentication() {
        mutableState.update { it.copy(authenticationContact = null, authenticationLookupComplete = false, failure = null) }
        try {
            val contact = dependencies.data.contactFor(mutableState.value.session)
            mutableState.update { it.copy(authenticationContact = contact, authenticationLookupComplete = true) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(authenticationLookupComplete = true, failure = failure) }
            dependencies.diagnostics.report("find room contact", failure)
        }
    }

    suspend fun authenticate(password: String?, rememberPassword: Boolean) {
        val contact = mutableState.value.authenticationContact ?: return
        mutableState.update { it.copy(isAuthenticating = true, failure = null) }
        try {
            val joined = dependencies.service.join(contact, password?.takeIf(String::isNotEmpty), rememberPassword)
            mutableState.update {
                it.copy(session = joined, isAuthenticating = false, authenticationContact = null)
            }
            load()
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isAuthenticating = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(isAuthenticating = false, failure = failure) }
            dependencies.diagnostics.report("authenticate room", failure)
        }
    }

    suspend fun reconnect() {
        mutableState.update { it.copy(isAuthenticating = true, failure = null) }
        try {
            val connected = dependencies.service.reconnect(mutableState.value.session)
            mutableState.update { it.copy(session = connected, isAuthenticating = false) }
            load()
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isAuthenticating = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(isAuthenticating = false, failure = failure) }
            dependencies.diagnostics.report("reconnect room", failure)
        }
    }

    fun updateDraft(value: String) = mutableState.update { it.copy(draft = value) }

    suspend fun send(): Boolean {
        val current = mutableState.value
        if (!current.session.canPost || !current.session.isConnected) return false
        return post(current.draft, clearDraft = true)
    }

    suspend fun sendAgain(message: RoomMessageDTO): Boolean {
        if (!message.isFromSelf || message.sessionID != mutableState.value.session.id) return false
        return post(message.text, clearDraft = false)
    }

    private suspend fun post(text: String, clearDraft: Boolean): Boolean {
        val current = mutableState.value
        if (text.isEmpty() || current.isSending) return false
        mutableState.update { it.copy(isSending = true, failure = null) }
        return try {
            val message = dependencies.service.post(current.session, text)
            appendIfNew(message)
            mutableState.update {
                it.copy(draft = if (clearDraft && it.draft == text) "" else it.draft, isSending = false)
            }
            scheduleReload()
            true
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(isSending = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(isSending = false, failure = failure) }
            dependencies.diagnostics.report("send room message", failure)
            false
        }
    }

    suspend fun retry(messageId: UUID) {
        try {
            val updated = dependencies.service.retry(mutableState.value.session, messageId)
            mutableState.update { current ->
                val messages = current.messages.map { if (it.id == messageId) updated else it }
                current.copy(messages = messages, rows = rows(messages), failure = null)
            }
            scheduleReload()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update { it.copy(failure = failure) }
            dependencies.diagnostics.report("retry room message", failure)
        }
    }

    fun clearFailure() = mutableState.update { it.copy(failure = null) }

    fun reportLinkFailure(failure: Exception) {
        mutableState.update { it.copy(failure = failure) }
        dependencies.diagnostics.report("load room link contacts", failure)
    }

    fun reportActionFailure(failure: Exception) {
        mutableState.update { it.copy(failure = failure) }
        dependencies.diagnostics.report("room message action", failure)
    }

    private fun appendIfNew(message: RoomMessageDTO) {
        mutableState.update { current ->
            if (current.messages.any { it.id == message.id }) return@update current
            val insertion = current.messages.indexOfFirst { it.timestamp > message.timestamp }
                .let { if (it < 0) current.messages.size else it }
            val messages = current.messages.toMutableList().apply { add(insertion, message) }
            current.copy(messages = messages, rows = rows(messages))
        }
    }

    private fun scheduleReload() {
        // Events invalidate snapshots already being fetched, even within a coalesced burst.
        generation++
        if (reloadJob?.isActive == true) return
        val pending = scope.launch(start = CoroutineStart.LAZY) {
            try {
                awaitReloadDebounce()
                reloadJob = null
                load()
            } finally {
                if (reloadJob === coroutineContext[Job]) reloadJob = null
            }
        }
        reloadJob = pending
        pending.start()
    }

    companion object {
        const val GROUPING_GAP_SECONDS = 300L
        private val RELOAD_DEBOUNCE = 50.milliseconds

        fun rows(messages: List<RoomMessageDTO>): List<RoomMessageRow> = messages.mapIndexed { index, message ->
            val previous = messages.getOrNull(index - 1)
            val next = messages.getOrNull(index + 1)
            val continuesFromPrevious = previous?.let { incomingClusterContinues(it, message) } == true
            val continuesToNext = next?.let { incomingClusterContinues(message, it) } == true
            val gap = previous?.let { kotlin.math.abs(message.timestamp.toLong() - it.timestamp.toLong()) }
            RoomMessageRow(
                message = message,
                showTimestamp = previous == null || gap!! > GROUPING_GAP_SECONDS,
                showSenderName = !message.isFromSelf && !continuesFromPrevious,
                showAvatar = !message.isFromSelf && !continuesToNext,
            )
        }

        fun incomingClusterContinues(earlier: RoomMessageDTO, later: RoomMessageDTO): Boolean =
            !earlier.isFromSelf && !later.isFromSelf &&
                kotlin.math.abs(later.timestamp.toLong() - earlier.timestamp.toLong()) <= GROUPING_GAP_SECONDS &&
                earlier.authorKeyPrefix == later.authorKeyPrefix
    }
}
