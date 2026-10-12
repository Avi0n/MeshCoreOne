// AndroidOnly: WP-308 Feature-owned seams (type-placement decision: feature modules may not depend on core:services/data/runtime, so the composer declares what it needs and the app layer binds it).
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DirectMessageEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/** Where a composed message goes. Mirrors `ChatConversationType` (dm / channel) for the composer only. */
sealed interface ComposerTarget {
    val conversationId: ChatConversationID

    data class Direct(val contact: ContactDTO) : ComposerTarget {
        override val conversationId: ChatConversationID get() = ChatConversationID.dm(contact.radioId, contact.id)
    }

    data class Channel(val channel: ChannelDTO) : ComposerTarget {
        override val conversationId: ChatConversationID get() = ChatConversationID.channel(channel.radioId, channel.index)
        val isPublicStyle: Boolean get() = !channel.isEncryptedChannel
    }
}

/**
 * Pending creation followed by the service-owned durable queue, as in `ChatViewModel+SendQueues`.
 * The app binds creation to MessageService and enqueue/signalling to ChatSendQueuePort. Enqueue
 * returns after durable acceptance, not wire delivery; attempts and timestamp policy belong to
 * the queue. Implementations validate length again and surface typed creation/enqueue failures.
 */
interface ComposerSendPort {
    suspend fun createPendingDirect(
        text: String, contact: ContactDTO, textType: TextType = TextType.PLAIN, replyToId: UUID? = null,
    ): MessageDTO
    suspend fun createPendingChannel(
        text: String, channelIndex: UByte, radioId: RadioId, textType: TextType = TextType.PLAIN,
    ): MessageDTO
    suspend fun enqueueDM(envelope: DirectMessageEnvelope)
    suspend fun enqueueChannel(envelope: ChannelMessageEnvelope)

    /** The caller already persisted the retry row; signal the queue without inserting it again. */
    suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope)

    /** Mark the saved message failed unless delivered, publishing its actual status/event through the app adapter. */
    suspend fun recordLocalEnqueueFailure(messageId: UUID)
}

/** The connected node as the composer sees it (iOS `appState.connectedDevice` subset). */
data class ComposerDeviceInfo(
    val nodeName: String,
    val publicKey: Bytes,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val hasLocation: Boolean = false,
)

/** Phone location access for the "Share Location" entry (iOS `appState.locationService`). */
interface ComposerLocationSource {
    val isAuthorized: Boolean
    val currentCoordinate: Coordinate?

    /** A fresh fix, or null on timeout/denial. Must be cancellable. */
    suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate?
}

/** Live app facts the composer reads. */
interface ComposerEnvironment {
    val connectionState: StateFlow<DeviceConnectionState>
    val device: StateFlow<ComposerDeviceInfo?>

    /** Contacts offered by the `@` picker (type filtering is the composer's job). */
    val mentionContacts: StateFlow<List<ContactDTO>>

    /** Mesh name -> most recent message timestamp, for ordering channel mention suggestions. */
    val recentSenderOrder: StateFlow<Map<String, UInt>>
    val location: ComposerLocationSource
}

/** Delay seam so the 1 s send cooldown is deterministic on the JVM (no coroutines-test on the locked classpath). */
fun interface ComposerTimer {
    suspend fun delay(millis: Long)
}

fun interface ComposerDiagnostics {
    fun report(operation: String, failure: Throwable)
}
