package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.ContactFetchResult
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshContact
import java.util.concurrent.atomic.AtomicBoolean

internal class ConnectionInitialContactReturnSession(
    publicKey: ByteArray,
    frames: List<MeshContact>,
    holdReturn: Boolean = false,
) : FakeMeshSession(selfInfo = defaultSelfInfo(publicKey), contacts = frames) {
    val completed = CompletableDeferred<Pair<Job, ContactFetchResult>>()
    val release = CompletableDeferred<Unit>()
    private val hold = AtomicBoolean(holdReturn)
    var rejectedClosedDispatches = 0
        private set

    init {
        channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = emptyList())
    }

    override suspend fun getContactsReportingTotal(since: Long?): ContactFetchResult {
        rejectClosed()
        val result = super.getContactsReportingTotal(since).let { fetch ->
            fetch.copy(
                contacts = fetch.contacts.map {
                    it.copy(publicKey = it.publicKey.copyOf(), outPath = it.outPath.copyOf())
                },
            )
        }
        if (hold.compareAndSet(true, false)) {
            completed.complete(currentCoroutineContext().job to result)
            release.await()
        }
        return result
    }

    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult {
        rejectClosed()
        return super.getChannels(indices)
    }

    override suspend fun getChannel(index: Int): ChannelInfo {
        rejectClosed()
        return super.getChannel(index)
    }

    override suspend fun getMessage(): GetMessageResult {
        rejectClosed()
        return super.getMessage()
    }

    private fun rejectClosed() {
        if (closed) {
            rejectedClosedDispatches++
            throw MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, "session closed")
        }
    }
}
