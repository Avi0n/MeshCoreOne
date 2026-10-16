package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshBridgeException
import java.util.concurrent.atomic.AtomicBoolean

internal class ConnectionChannelFrameSession(
    publicKey: ByteArray,
    configured: List<ChannelInfo>,
    holdReturn: Boolean = false,
) : FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) {
    val completed = CompletableDeferred<Job>()
    val release = CompletableDeferred<Unit>()
    private val hold = AtomicBoolean(holdReturn)
    var forwarded = false
        private set
    var closedBacklogAdmissions = 0
        private set
    val requests = mutableListOf<List<Byte>>()

    init {
        channelBatch = CommandResult.ChannelsResult(
            received = (0 until SLOT_COUNT).map { index ->
                configured.firstOrNull { it.index == index } ?: ChannelInfo(index, "", ByteArray(SECRET_BYTES))
            },
            missing = emptyList(),
        )
    }

    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult {
        rejectClosed()
        requests += indices.toList()
        val result = super.getChannels(indices).let { value ->
            value.copy(
                received = value.received.map {
                    it.copy(secret = it.secret.copyOf())
                },
                missing = value.missing.toList(),
            )
        }
        if (hold.compareAndSet(true, false)) {
            check(result.received.size == SLOT_COUNT && result.missing.isEmpty())
            completed.complete(currentCoroutineContext().job)
            release.await()
            forwarded = true
        }
        return result
    }

    override suspend fun getChannel(index: Int): ChannelInfo {
        rejectClosed()
        return super.getChannel(index)
    }

    override suspend fun getMessage(): GetMessageResult {
        if (closed) closedBacklogAdmissions++
        rejectClosed()
        return super.getMessage()
    }

    override suspend fun startAutoMessageFetching() {
        rejectClosed()
        super.startAutoMessageFetching()
    }

    private fun rejectClosed() {
        if (closed) throw MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, "session closed")
    }

    companion object {
        const val SLOT_COUNT = 8
        const val SECRET_BYTES = 16
    }
}
