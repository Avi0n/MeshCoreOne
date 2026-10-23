// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Room/RoomAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.rooms

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface RoomConversationDataSource {
    suspend fun fetchMessages(session: RemoteNodeSessionDTO): List<RoomMessageDTO>
    suspend fun refreshSession(session: RemoteNodeSessionDTO): RemoteNodeSessionDTO?
    suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO?
    suspend fun markAsRead(session: RemoteNodeSessionDTO)
    suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO)
}

interface RoomConversationService {
    suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean): RemoteNodeSessionDTO
    suspend fun reconnect(session: RemoteNodeSessionDTO): RemoteNodeSessionDTO
    suspend fun post(session: RemoteNodeSessionDTO, text: String): RoomMessageDTO
    suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID): RoomMessageDTO
}

sealed interface RoomConversationEvent {
    val sessionId: UUID

    data class MessageReceived(
        override val sessionId: UUID,
        val message: RoomMessageDTO,
    ) : RoomConversationEvent

    data class MessageChanged(
        override val sessionId: UUID,
        val messageId: UUID,
    ) : RoomConversationEvent

    data class MessageFailed(
        override val sessionId: UUID,
        val messageId: UUID,
    ) : RoomConversationEvent

    data class ConnectionRecovered(
        override val sessionId: UUID,
        val session: RemoteNodeSessionDTO,
    ) : RoomConversationEvent
}

fun interface RoomConversationDiagnostics {
    fun report(operation: String, failure: Throwable)
}

interface RoomConversationDependencies {
    val data: RoomConversationDataSource
    val service: RoomConversationService
    val events: Flow<RoomConversationEvent>
    val diagnostics: RoomConversationDiagnostics get() = RoomConversationDiagnostics { _, _ -> }
    suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) = Unit
    suspend fun updateBadgeCount() = Unit
    fun conversationsChanged() = Unit
}
