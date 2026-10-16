package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.sync.Mutex
import one.meshcore.shared.model.Uuid

/**
 * Pending queues and in-flight send keys for one [ReactionService]. They share one mutex so an index
 * and a send cannot interleave, the same serialization the Swift actor gave this state.
 */
internal class ReactionServiceState(val now: () -> Double) {
    val mutex = Mutex()
    val pendingChannel = LinkedHashMap<PendingChannelKey, MutableList<PendingReaction>>()
    val pendingDirect = LinkedHashMap<PendingDirectKey, MutableList<PendingDmReaction>>()
    val inFlight = mutableSetOf<String>()

    internal data class PendingChannelKey(val channelIndex: Int, val targetSender: String, val messageHash: String)

    internal data class PendingDirectKey(val contactID: Uuid, val messageHash: String)
}
