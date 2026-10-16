package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.contact.ContactDeviceException
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshContact
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals

internal class ConnectionSyncSuppressionSession(publicKey: ByteArray, holdBacklog: Boolean, resync: Boolean = false) :
    FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) {
    val contactEntered = CompletableDeferred<Job>()
    val backlogCompleted = CompletableDeferred<Job>()
    val backlogRelease = CompletableDeferred<Unit>()
    private val hold = AtomicBoolean(holdBacklog)
    private val heldMessageCount = if (resync) RESYNC_MESSAGE_COUNT else INITIAL_MESSAGE_COUNT

    init {
        contactsGate = CompletableDeferred()
        channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = emptyList())
        if (resync) contactsFailures += ContactDeviceException(DEVICE_ERROR_CODE)
    }

    override suspend fun getContacts(since: Long?): List<MeshContact> {
        contactEntered.complete(currentCoroutineContext().job)
        return super.getContacts(since)
    }

    override suspend fun getMessage(): GetMessageResult {
        val result = super.getMessage()
        assertEquals(GetMessageResult.NoMoreMessages, result)
        if (getMessageCount == heldMessageCount && hold.compareAndSet(true, false)) {
            backlogCompleted.complete(currentCoroutineContext().job)
            backlogRelease.await()
        }
        return result
    }

    fun releaseAll() {
        contactsGate?.complete(Unit)
        backlogRelease.complete(Unit)
    }
    companion object {
        private const val INITIAL_MESSAGE_COUNT = 1
        private const val RESYNC_MESSAGE_COUNT = 2
        private const val DEVICE_ERROR_CODE = 0x03
    }
}
