package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.OwnerInfoResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.remotenode.LPPDataPoint
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.room.RoomAdminSession
import kotlin.time.Duration

internal class RemoteSectionLazySession : NodeSession {
    override val events = MutableSharedFlow<MeshEvent>()
    val calls = mutableListOf<String>()
    val ownerReply = CompletableDeferred<OwnerInfoResponse>()
    val telemetryReply = CompletableDeferred<TelemetryResponse>()
    val roomOwnerReply = CompletableDeferred<String>()
    val readJobs = mutableListOf<Job>()
    var cancelledReads = 0
        private set

    override suspend fun sendLogin(destination: ByteArray, password: String): Nothing = unexpected("login")

    override suspend fun sendLogout(destination: ByteArray): Nothing = unexpected("logout")

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): Nothing = unexpected("keepalive")

    override suspend fun sendCommand(destination: ByteArray, command: String): Nothing = unexpected("cli:$command")

    override suspend fun requestStatus(publicKey: ByteArray): Nothing = unexpected("status")

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse {
        calls += "telemetry"
        return read { telemetryReply.await() }
    }

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse {
        calls += "owner"
        return read { ownerReply.await() }
    }

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): Nothing = unexpected("neighbors")

    val room = object : RoomAdminSession {
        override suspend fun requestStatus(publicKey: ByteArray, timeout: Duration): Nothing = unexpected("room-status")

        override suspend fun requestTelemetry(publicKey: ByteArray, timeout: Duration): TelemetryResponse {
            calls += "room-telemetry"
            return read { telemetryReply.await() }
        }

        override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String {
            calls += "room-cli:$command"
            check(command == "get owner.info") { "Unexpected room command: $command" }
            return read { roomOwnerReply.await() }
        }

        override suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): Nothing =
            unexpected("room-raw:$command")
    }

    fun completeTelemetry(publicKey: ByteArray, points: List<LPPDataPoint> = emptyList()) {
        check(
            telemetryReply.complete(
                TelemetryResponse(publicKey.copyOf(PREFIX_BYTES), null, ByteArray(0), points),
            ),
        )
    }

    fun cancelGates() {
        ownerReply.cancel()
        telemetryReply.cancel()
        roomOwnerReply.cancel()
    }

    private suspend fun <T> read(block: suspend () -> T): T {
        readJobs += checkNotNull(currentCoroutineContext()[Job])
        return try {
            block()
        } catch (cancelled: CancellationException) {
            cancelledReads++
            throw cancelled
        }
    }

    private fun unexpected(operation: String): Nothing {
        calls += operation
        error("Unexpected fixture operation: $operation")
    }

    private companion object {
        const val PREFIX_BYTES = 6
    }
}
