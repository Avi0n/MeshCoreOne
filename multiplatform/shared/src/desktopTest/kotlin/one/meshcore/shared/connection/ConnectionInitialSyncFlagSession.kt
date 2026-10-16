package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshContact
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals

internal class ConnectionInitialSyncFlagSession(publicKey: ByteArray, holdBacklog: Boolean) :
    FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) {
    val contactEntered = CompletableDeferred<Job>()
    val backlogCompleted = CompletableDeferred<Job>()
    val backlogRelease = CompletableDeferred<Unit>()
    private val hold = AtomicBoolean(holdBacklog)

    init {
        contactsGate = CompletableDeferred()
        channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = emptyList())
    }

    override suspend fun getContacts(since: Long?): List<MeshContact> {
        contactEntered.complete(currentCoroutineContext().job)
        return super.getContacts(since)
    }

    override suspend fun getMessage(): GetMessageResult {
        val result = super.getMessage()
        assertEquals(GetMessageResult.NoMoreMessages, result)
        if (hold.compareAndSet(true, false)) {
            backlogCompleted.complete(currentCoroutineContext().job)
            backlogRelease.await()
        }
        return result
    }

    fun releaseAll() {
        contactsGate?.complete(Unit)
        backlogRelease.complete(Unit)
    }
}
