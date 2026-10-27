// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+EventStream.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+SendQueues.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.timeline

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

sealed interface TimelineConversation {
    val id: ChatConversationID
    val unreadCount: Long

    data class Direct(val contact: ContactDTO) : TimelineConversation {
        override val id = ChatConversationID.dm(contact.radioId, contact.id)
        override val unreadCount get() = contact.unreadCount
    }

    data class Channel(val channel: ChannelDTO) : TimelineConversation {
        override val id = ChatConversationID.channel(channel.radioId, channel.index)
        override val unreadCount get() = channel.unreadCount
    }
}

interface TimelineDataSource {
    /** Returns a newest database page in chronological order. Offset counts unfiltered database rows. */
    suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int): List<MessageDTO>

    /**
     * Returns the newest chronological window that keeps every row at or after [anchorSortDate] (the oldest loaded
     * row on a refresh, null on a first open) and at least [floorLimit] rows. `hasMore` reports rows beyond it.
     */
    suspend fun fetchMessageWindow(conversation: TimelineConversation, anchorSortDate: Instant?, floorLimit: Int): MessageWindow
    suspend fun fetchMessage(messageId: UUID): MessageDTO?
    suspend fun unreadAnchorMessageId(conversation: TimelineConversation): UUID?
    suspend fun clearUnread(conversation: TimelineConversation)
    suspend fun markFailedSendsSeen(conversation: TimelineConversation)
}

fun interface TimelineRetryService {
    suspend fun retry(message: MessageDTO, conversation: TimelineConversation)
}

interface TimelineDraftStore {
    fun get(conversationId: ChatConversationID): String?
    fun set(conversationId: ChatConversationID, text: String?)
}

sealed interface TimelineEvent {
    val conversationId: ChatConversationID

    data class MessageReceived(
        override val conversationId: ChatConversationID,
        val message: MessageDTO,
    ) : TimelineEvent

    data class StatusChanged(
        override val conversationId: ChatConversationID,
        val messageId: UUID,
        val status: MessageStatus,
        val roundTripTime: UInt? = null,
    ) : TimelineEvent

    data class MessageChanged(
        override val conversationId: ChatConversationID,
        val messageId: UUID,
    ) : TimelineEvent
}

fun interface TimelineDiagnostics {
    fun report(operation: String, failure: Throwable)
}

interface TimelineContent {
    val enabled: Boolean
    fun containsUrls(text: String): Boolean
    suspend fun prefetch(text: String, isChannelMessage: Boolean)
}

interface ChatTimelineDependencies {
    val data: TimelineDataSource
    val retryService: TimelineRetryService
    val draftStore: TimelineDraftStore
    val events: Flow<TimelineEvent>
    val diagnostics: TimelineDiagnostics get() = TimelineDiagnostics { _, _ -> }
    val content: TimelineContent? get() = null
    /** Sent outgoing reactions render as badges, so the timeline hides them while the database keeps the rows. */
    fun isHiddenOutgoingReaction(message: MessageDTO): Boolean = false
    fun admissionIdentity(): SessionToken? = null
    fun isCurrentAdmission(identity: SessionToken?): Boolean = true
    suspend fun syncChannelFloodScope(channel: ChannelDTO) = Unit
}
