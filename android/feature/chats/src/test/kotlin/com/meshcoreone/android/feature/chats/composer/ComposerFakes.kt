// AndroidOnly: WP-308 JVM fakes: scripted send port, environment, virtual-clock timer and draft store.
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.feature.chats.timeline.TimelineDraftStore
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

internal class ManualTimer : ComposerTimer {
    val pending = ArrayList<CompletableDeferred<Unit>>()
    val requested = ArrayList<Long>()

    override suspend fun delay(millis: Long) {
        requested += millis
        val gate = CompletableDeferred<Unit>()
        pending += gate
        gate.await()
    }

    /** Advances the virtual clock past every outstanding delay. */
    fun advance() {
        val gates = pending.toList()
        pending.clear()
        gates.forEach { it.complete(Unit) }
    }
}

internal class MemoryDrafts(initial: Map<ChatConversationID, String> = emptyMap()) : TimelineDraftStore {
    val values = initial.toMutableMap()
    override fun get(conversationId: ChatConversationID): String? = values[conversationId]
    override fun set(conversationId: ChatConversationID, text: String?) {
        if (text == null) values.remove(conversationId) else values[conversationId] = text
    }
}

internal class FakeLocation(
    override var isAuthorized: Boolean = false,
    override var currentCoordinate: Coordinate? = null,
    var fresh: Coordinate? = null,
    val gate: CompletableDeferred<Unit>? = null,
) : ComposerLocationSource {
    var requests = 0
    override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? {
        requests++
        gate?.await()
        return fresh
    }
}

internal class FakeEnvironment(
    connection: DeviceConnectionState = DeviceConnectionState.READY,
    device: ComposerDeviceInfo? = null,
    contacts: List<ContactDTO> = emptyList(),
    override val location: FakeLocation = FakeLocation(),
) : ComposerEnvironment {
    override val connectionState = MutableStateFlow(connection)
    override val device = MutableStateFlow(device)
    override val mentionContacts = MutableStateFlow(contacts)
    override val recentSenderOrder = MutableStateFlow<Map<String, UInt>>(emptyMap())
}

/** Records calls in order; each stage can be scripted to throw or to suspend on a gate. */
internal class FakeSendPort(val radio: RadioId) : ComposerSendPort {
    val calls = ArrayList<String>()
    val pendingMessages = ArrayList<MessageDTO>()
    val directEnvelopes = ArrayList<DirectMessageEnvelope>()
    val channelEnvelopes = ArrayList<ChannelMessageEnvelope>()
    var createFailure: Throwable? = null
    var createGate: CompletableDeferred<Unit>? = null
    var sendFailure: Throwable? = null
    var sendGate: CompletableDeferred<Unit>? = null

    private fun row(text: String, contactId: UUID?, channelIndex: UByte? = null) = MessageDTO(
        id = UUID.nameUUIDFromBytes("row-${calls.size}".toByteArray()), radioId = radio, contactID = contactId,
        channelIndex = channelIndex, text = text,
        timestamp = 1u, createdAt = Instant.EPOCH, sortDate = Instant.EPOCH, direction = MessageDirection.OUTGOING,
        status = MessageStatus.PENDING,
    )

    override suspend fun createPendingDirect(text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?): MessageDTO {
        calls += "createPendingDirect:$text"
        createGate?.await()
        createFailure?.let { throw it }
        return row(text, contact.id).also(pendingMessages::add)
    }

    override suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
        calls += "enqueueDM"
        directEnvelopes += envelope
        sendGate?.await()
        sendFailure?.let { throw it }
    }

    override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) =
        error("signal-only retry belongs to the timeline retry path")

    override suspend fun createPendingChannel(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType): MessageDTO {
        calls += "createPendingChannel:$channelIndex:$text"
        createGate?.await()
        createFailure?.let { throw it }
        return row(text, null, channelIndex).also(pendingMessages::add)
    }

    override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
        calls += "enqueueChannel"
        channelEnvelopes += envelope
        sendGate?.await()
        sendFailure?.let { throw it }
    }
}
