package one.meshcore.shared.chat

import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshEvent

/**
 * The device-session seam the incoming-ingest layer depends on: the backlog-drain fetch and the pushed
 * event stream the live monitor consumes. The narrow port keeps [MessagePoller] in commonMain and
 * independently testable. [one.meshcore.shared.connection.IngestSessionAdapter] bridges the live session.
 * Mirrors the subset of `MeshCoreSessionProtocol` that `MessagePollingService` calls.
 */
interface IngestSession {
    /** The hot stream of pushed device events; the live monitor filters it for received messages. */
    val events: SharedFlow<MeshEvent>

    /**
     * Fetches the next queued backlog message, or [GetMessageResult.NoMoreMessages] when the drain is
     * done. The getMessage-coalescing lost-wakeup fix lives below the frozen bridge, so concurrent
     * callers are safe and the poller adds no app-level locking around this call.
     */
    suspend fun getMessage(): GetMessageResult
}
