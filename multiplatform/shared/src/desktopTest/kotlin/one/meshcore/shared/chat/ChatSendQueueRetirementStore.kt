package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import one.meshcore.shared.model.Uuid

internal class ChatSendQueueRetirementStore(private val delegate: SendQueueStore) : SendQueueStore by delegate {
    var nextWrite: WriteGate? = null
    var nextFetch: Gate? = null
    var nextPurge: Gate? = null
    var writeCalls = 0
        private set
    var legacyPurgeCalls = 0
        private set

    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDto): Int = write {
        delegate.insertPendingSendAssigningSequence(dto)
    }

    override suspend fun replacePendingSendForRetry(messageID: Uuid, dto: PendingSendDto): Int = write {
        delegate.replacePendingSendForRetry(messageID, dto)
    }

    private suspend fun write(block: suspend () -> Int): Int {
        writeCalls += 1
        val gate = nextWrite
        nextWrite = null
        if (gate != null && !gate.afterCommit) gate.waitForRelease()
        val result = block()
        if (gate != null && gate.afterCommit) gate.waitForRelease()
        return result
    }

    override suspend fun fetchPendingSends(radioID: Uuid): List<PendingSendDto> {
        val rows = delegate.fetchPendingSends(radioID)
        val gate = nextFetch
        nextFetch = null
        gate?.waitForRelease()
        return rows
    }

    override suspend fun purgeOrphanPendingSends(): Int {
        val count = delegate.purgeOrphanPendingSends()
        val gate = nextPurge
        nextPurge = null
        gate?.waitForRelease()
        return count
    }

    override suspend fun purgeLegacyAttemptCountRows(): Int {
        legacyPurgeCalls += 1
        return delegate.purgeLegacyAttemptCountRows()
    }

    open class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        suspend fun waitForRelease() {
            entered.complete(Unit)
            release.await()
        }
    }

    class WriteGate(val afterCommit: Boolean) : Gate()
}
