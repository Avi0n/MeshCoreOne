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
import one.meshcore.shared.meshcore.MeshContact
import java.util.concurrent.atomic.AtomicBoolean

internal class ConnectionInitialBacklogMaterializerSession(
    publicKey: ByteArray,
    frames: List<MeshContact>,
    backlog: MutableList<GetMessageResult> = mutableListOf(),
    holdReturn: Boolean = false,
) : FakeMeshSession(selfInfo = defaultSelfInfo(publicKey), contacts = frames, backlog = backlog) {
    val completed = CompletableDeferred<Pair<Job, MeshContact>>()
    val release = CompletableDeferred<Unit>()
    private val hold = AtomicBoolean(holdReturn)
    var completedReturnForwarded = false
        private set
    var closedMessageRejections = 0
        private set
    var nextMessageClosedAtAdmission: Boolean? = null
        private set
    var nextMessageNormalResult: GetMessageResult? = null
        private set
    var beforeNextMessage: (suspend () -> Unit)? = null

    init {
        channelBatch = CommandResult.ChannelsResult(received = emptyList(), missing = emptyList())
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? {
        rejectClosed()
        val contact = super.getContact(publicKey)?.let {
            it.copy(publicKey = it.publicKey.copyOf(), outPath = it.outPath.copyOf())
        }
        if (hold.compareAndSet(true, false)) {
            completed.complete(currentCoroutineContext().job to requireNotNull(contact))
            release.await()
            completedReturnForwarded = true
        }
        return contact
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
        val next = getMessageCount == 1
        if (next) nextMessageClosedAtAdmission = closed
        if (closed) closedMessageRejections++
        rejectClosed()
        if (getMessageCount == 1) beforeNextMessage?.invoke()
        val result = super.getMessage()
        if (next) nextMessageNormalResult = result
        return result
    }

    private fun rejectClosed() {
        if (closed) throw MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, "session closed")
    }
}
