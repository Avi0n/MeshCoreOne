// PortedFrom: MC1/Views/Chats/Components/ChatInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChatConversationInputBar.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Mentions/ChatConversationMentionOverlay.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+SendQueues.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+EventStream.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.feature.chats.timeline.TimelineDraftStore
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns one conversation's composer: the draft (persisted through the shared [TimelineDraftStore]),
 * the UTF-8 byte budget, `@` mention suggestions, the send button state machine and the share/emoji
 * inserts. State is a [StateFlow] of immutable [ComposerUiState]; the injected [scope] and [timer]
 * keep it deterministic on the JVM.
 *
 * Send contract (iOS `ChatInputBar.send`): the trimmed draft is captured, the field clears at once,
 * and the button stays disabled for [ComposerLimits.SEND_COOLDOWN_MILLIS] and while a send is in
 * flight to durable enqueue acceptance. A pending row is created first so a later failure leaves a
 * visible failed message that the timeline retries; when creating that row fails nothing exists,
 * so the captured text returns to the draft (unless the user already typed something new).
 */
class ComposerStateHolder(
    private val target: ComposerTarget,
    private val sendPort: ComposerSendPort,
    private val drafts: TimelineDraftStore,
    private val environment: ComposerEnvironment,
    private val scope: CoroutineScope,
    private val timer: ComposerTimer,
    private val diagnostics: ComposerDiagnostics = ComposerDiagnostics { _, _ -> },
    private val locale: java.util.Locale = java.util.Locale.getDefault(),
) {
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<ComposerUiState> = mutableState.asStateFlow()

    private var cooldownJob: Job? = null
    private var sendJob: Job? = null
    private var shareJob: Job? = null
    private val observers = ArrayList<Job>()

    init {
        observers += scope.launch {
            environment.connectionState.collect { connection ->
                mutableState.update { it.copy(isConnected = connection == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY) }
            }
        }
        observers += scope.launch {
            environment.device.collect { device ->
                mutableState.update { it.copy(maxBytes = maxBytesFor(device)) }
            }
        }
        observers += scope.launch {
            environment.mentionContacts.collect { mutableState.update(::withMentions) }
        }
        observers += scope.launch {
            environment.recentSenderOrder.collect { mutableState.update(::withMentions) }
        }
    }

    private fun initialState(): ComposerUiState {
        val draft = drafts.get(target.conversationId).orEmpty()
        val encrypted = when (target) {
            is ComposerTarget.Direct -> true
            is ComposerTarget.Channel -> target.channel.isEncryptedChannel
        }
        return ComposerUiState(draft = draft, maxBytes = maxBytesFor(environment.device.value), isEncrypted = encrypted,
            isConnected = environment.connectionState.value == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY)
            .let(::withMentions)
    }

    private fun maxBytesFor(device: ComposerDeviceInfo?): Int =
        ComposerLimits.maxBytes(target, device?.let { ComposerText.utf8Length(it.nodeName) } ?: 0)

    /** User typed or pasted; also persists the draft (empty clears it). */
    fun onDraftChanged(text: String) {
        mutableState.update { withMentions(it.copy(draft = text, sendPhase = clearFailure(it.sendPhase))) }
        drafts.set(target.conversationId, text.ifEmpty { null })
    }

    private fun clearFailure(phase: ComposerSendPhase): ComposerSendPhase =
        if (phase is ComposerSendPhase.Failed) ComposerSendPhase.Idle else phase

    private fun withMentions(state: ComposerUiState): ComposerUiState {
        val query = MentionUtilities.detectActiveMention(state.draft)
        val suggestions = if (query == null) emptyList() else MentionUtilities
            .filterContacts(environment.mentionContacts.value, query, environment.recentSenderOrder.value, locale)
            .take(ComposerLimits.MAX_MENTION_SUGGESTIONS)
        return state.copy(mentionQuery = query, mentionSuggestions = suggestions)
    }

    fun requestFocus() = mutableState.update { it.copy(focusRequest = it.focusRequest + 1) }

    /** Replaces the active `@query` with `@[name] ` (mesh name, not nickname) and re-requests focus. */
    fun selectMention(contact: ContactDTO) {
        val current = mutableState.value
        onDraftChanged(MentionUtilities.insertMention(current.draft, contact.name))
        requestFocus()
    }

    fun dismissMentions() = mutableState.update { it.copy(mentionQuery = null, mentionSuggestions = emptyList()) }

    /** "Mention sender" action: appends `@[name] ` to the draft. */
    fun appendMention(name: String) {
        onDraftChanged(MentionUtilities.appendMention(name, mutableState.value.draft))
        requestFocus()
    }

    /** Share-menu insert (location text, contact token, my-info token) at the end of the draft. */
    fun insertShared(shared: String) {
        onDraftChanged(ComposerShare.insertShared(mutableState.value.draft, shared))
        requestFocus()
    }

    /**
     * Inserts [insert] over the selection [selectionStart]..[selectionEnd] (UTF-16 offsets, any order).
     * Returns the caret offset after the insert so the field can restore it.
     */
    fun insertAtSelection(insert: String, selectionStart: Int, selectionEnd: Int): Int {
        val draft = mutableState.value.draft
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, draft.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, draft.length)
        onDraftChanged(draft.substring(0, start) + insert + draft.substring(end))
        return start + insert.length
    }

    fun canShareLocation(): Boolean = ComposerShare.canShareLocation(
        environment.location.takeIf { it.isAuthorized }?.currentCoordinate, nodeCoordinate(), environment.location.isAuthorized,
    )

    fun canShareMyInfo(): Boolean = environment.device.value?.let { ComposerShare.canShareMyInfo(it.publicKey, it.nodeName) } == true

    private fun nodeCoordinate() = environment.device.value?.takeIf { it.hasLocation }
        ?.let { com.meshcoreone.android.core.model.Coordinate(it.latitude, it.longitude) }

    /** iOS `shareLocation`: a fresh fix when authorized, else the last phone fix, else the node's. */
    fun shareLocation(): Job {
        shareJob?.cancel()
        return scope.launch {
            val location = environment.location
            val fresh = if (location.isAuthorized) location.requestCurrentLocation(ComposerShare.LOCATION_FIX_TIMEOUT_MILLIS) else null
            val coordinate = fresh ?: location.takeIf { it.isAuthorized }?.currentCoordinate ?: nodeCoordinate() ?: return@launch
            insertShared(ComposerShare.locationText(coordinate))
        }.also { shareJob = it }
    }

    fun shareMyInfo() {
        val device = environment.device.value ?: return
        if (!ComposerShare.canShareMyInfo(device.publicKey, device.nodeName)) return
        insertShared(ComposerShare.myInfoToken(device.publicKey, device.nodeName))
    }

    /** Hardware-keyboard/IME send action: sends only when the button would be enabled. */
    fun send(): Boolean {
        val snapshot = mutableState.value
        if (!snapshot.canSend) return false
        val captured = ComposerText.trimmed(snapshot.draft)
        if (captured.isEmpty()) return false
        mutableState.update { it.copy(draft = "", isCoolingDown = true, sendPhase = ComposerSendPhase.Sending, mentionQuery = null, mentionSuggestions = emptyList()) }
        drafts.set(target.conversationId, null)
        cooldownJob?.cancel()
        cooldownJob = scope.launch {
            timer.delay(ComposerLimits.SEND_COOLDOWN_MILLIS)
            mutableState.update { it.copy(isCoolingDown = false) }
        }
        sendJob = scope.launch { deliver(captured) }
        return true
    }

    private suspend fun deliver(text: String) {
        var pendingId: UUID? = null
        try {
            val pending = createPending(text)
            pendingId = pending.id
            enqueuePending(pending)
            mutableState.update { it.copy(sendPhase = ComposerSendPhase.Idle) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            diagnostics.report("send", failure)
            val saved = pendingId != null
            mutableState.update { current ->
                val restored = if (!saved && current.draft.isEmpty()) text else current.draft
                withMentions(current.copy(draft = restored, sendPhase = ComposerSendPhase.Failed(classify(failure), saved)))
            }
            if (!saved) drafts.set(target.conversationId, mutableState.value.draft.ifEmpty { null })
            pendingId?.let { sendPort.recordLocalEnqueueFailure(it) }
        }
    }

    private suspend fun createPending(text: String) = when (target) {
        is ComposerTarget.Direct -> sendPort.createPendingDirect(text, target.contact, TextType.PLAIN, null)
        is ComposerTarget.Channel -> sendPort.createPendingChannel(text, target.channel.index, target.channel.radioId, TextType.PLAIN)
    }

    private suspend fun enqueuePending(message: MessageDTO) {
        when (target) {
            is ComposerTarget.Direct -> sendPort.enqueueDM(DirectMessageEnvelope(message.id, target.contact.id))
            is ComposerTarget.Channel -> sendPort.enqueueChannel(
                ChannelMessageEnvelope(
                    messageID = message.id,
                    channelIndex = target.channel.index,
                    isResend = false,
                    messageText = message.text,
                    messageTimestamp = message.timestamp,
                    localNodeName = environment.device.value?.nodeName,
                ),
            )
        }
    }

    private fun classify(failure: Throwable): ComposerSendFailure = when (failure) {
        is ChatSendQueueServiceException -> when (failure.error) {
            ChatSendQueueServiceError.NotConnected -> ComposerSendFailure.NOT_CONNECTED
            is ChatSendQueueServiceError.PersistFailed -> ComposerSendFailure.OTHER
        }
        is MessageServiceException -> when (failure.error) {
            MessageServiceError.NotConnected -> ComposerSendFailure.NOT_CONNECTED
            MessageServiceError.MessageTooLong -> ComposerSendFailure.MESSAGE_TOO_LONG
            MessageServiceError.ContactNotFound, MessageServiceError.ChannelNotFound,
            MessageServiceError.InvalidRecipient -> ComposerSendFailure.RECIPIENT_NOT_FOUND
            else -> ComposerSendFailure.OTHER
        }
        else -> ComposerSendFailure.OTHER
    }

    fun dismissFailure() = mutableState.update { it.copy(sendPhase = clearFailure(it.sendPhase)) }

    /** Cancels observers and in-flight work (screen leaves composition). */
    fun close() {
        observers.forEach(Job::cancel)
        cooldownJob?.cancel()
        sendJob?.cancel()
        shareJob?.cancel()
    }
}
