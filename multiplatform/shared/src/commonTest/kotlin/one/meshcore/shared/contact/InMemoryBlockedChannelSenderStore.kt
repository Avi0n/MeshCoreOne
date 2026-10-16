package one.meshcore.shared.contact

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.model.Uuid

/** In-memory blocked-sender store for service tests. */
class InMemoryBlockedChannelSenderStore : BlockedChannelSenderStore {
    private val mutex = Mutex()
    private val senders = mutableListOf<BlockedChannelSenderDto>()

    override suspend fun save(sender: BlockedChannelSenderDto) = mutex.withLock {
        val index = senders.indexOfFirst { it.radioID == sender.radioID && it.name == sender.name }
        if (index >= 0) {
            senders[index] = senders[index].copy(dateBlocked = sender.dateBlocked)
        } else {
            senders += sender
        }
    }

    override suspend fun delete(radioID: Uuid, name: String) = mutex.withLock {
        senders.removeAll { it.radioID == radioID && it.name == name }
        Unit
    }

    override suspend fun list(radioID: Uuid): List<BlockedChannelSenderDto> = mutex.withLock {
        senders.filter { it.radioID == radioID }.sortedByDescending { it.dateBlocked }
    }
}
