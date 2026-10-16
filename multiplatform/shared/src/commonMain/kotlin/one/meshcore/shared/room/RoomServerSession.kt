package one.meshcore.shared.room

import one.meshcore.shared.meshcore.MessageSentInfo

/** Direct-then-flood budget for one room post. */
data class RoomMessageRetry(val maxAttempts: Int, val floodAfter: Int, val maxFloodAttempts: Int)

/**
 * The device-session seam [RoomServerService] uses to post to a room and to establish a route for history
 * sync. A narrow room-in-package port over the androidMain-only `MeshCoreSession`, mirroring
 * [one.meshcore.shared.remotenode.NodeSession]: it carries the room *message* ops the shared login seam
 * does not, so the service stays in commonMain and is independently testable.
 * [one.meshcore.shared.connection.RoomServerSessionAdapter] bridges the live session.
 *
 * A command failure raises a [one.meshcore.shared.remotenode.MeshError]; the production adapter maps a
 * bridge exception to one and fakes throw it directly.
 */
interface RoomServerSession {
    /**
     * Posts [text] to the room at [destination] (the FULL 32-byte key, required for path reset) with [retry].
     * Returns the firmware [MessageSentInfo] when an end-to-end ACK arrived; returns null when all retries
     * were exhausted with no ACK (the packet likely reached the server — a `.sent` outcome, not a failure);
     * throws when the send itself failed.
     */
    suspend fun sendMessageWithRetry(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        retry: RoomMessageRetry,
    ): MessageSentInfo?

    /** Triggers path discovery toward [destination] so a flood-routed room can gain a direct route. */
    suspend fun sendPathDiscovery(destination: ByteArray)

    /** Requests the room's message history from [destination] over the currently known route. */
    suspend fun requestHistorySync(destination: ByteArray)
}
