package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import one.meshcore.shared.channel.ChannelSendTimeoutException
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.meshcore.CommandResult
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshBridgeException

internal class ConnectionSyncActivitySession(publicKey: ByteArray, private val holdBatch: Boolean) :
    FakeMeshSession(
        selfInfo = defaultSelfInfo(publicKey),
        contacts = listOf(
            sampleContact(
                publicKey.map {
                    (it + 5).toByte()
                }.toByteArray(),
                "Fixture peer",
            ),
        ),
    ) {
    val completed = CompletableDeferred<Job>()
    val release = CompletableDeferred<Unit>()
    var forwarded = false
        private set
    var closedBacklogAdmissions = 0
        private set

    init {
        channelBatch =
            CommandResult.ChannelsResult(received = emptyList(), missing = if (holdBatch) emptyList() else listOf(SLOT))
        if (!holdBatch) {
            channelSlots[SLOT] = ChannelInfo(SLOT, "Synthetic retry", ByteArray(16) { (it + 17).toByte() })
            channelSlotFailures[SLOT] = mutableListOf(ChannelSendTimeoutException(), ChannelSendTimeoutException())
        }
    }

    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult {
        rejectClosed()
        val result = super.getChannels(indices).let { value ->
            value.copy(
                received = value.received.map {
                    it.copy(secret = it.secret.copyOf())
                },
                missing = value.missing.toList(),
            )
        }
        if (holdBatch) {
            check(completed.complete(currentCoroutineContext().job))
            release.await()
            forwarded = true
        }
        return result
    }

    override suspend fun getChannel(index: Int): ChannelInfo {
        rejectClosed()
        val result = super.getChannel(index).let { it.copy(secret = it.secret.copyOf()) }
        if (!holdBatch && index == SLOT) {
            check(completed.complete(currentCoroutineContext().job))
            release.await()
            forwarded = true
        }
        return result
    }

    override suspend fun getMessage(): GetMessageResult {
        if (closed) closedBacklogAdmissions++
        rejectClosed()
        return super.getMessage()
    }

    private fun rejectClosed() {
        if (closed) throw MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, "session closed")
    }

    companion object {
        const val SLOT = 1
    }
}
