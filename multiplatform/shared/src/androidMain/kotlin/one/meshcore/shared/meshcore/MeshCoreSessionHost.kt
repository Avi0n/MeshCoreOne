package one.meshcore.shared.meshcore

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.jni.MeshBridgeNativeCallbacks
import one.meshcore.shared.meshcore.jni.MeshCoreNative
import one.meshcore.shared.transport.MeshLink
import org.swift.swiftkit.core.ClosableSwiftArena
import org.swift.swiftkit.core.SwiftArena
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import one.meshcore.bridge.MeshCoreBridge as NativeBridge

/** The bridge's unset sentinel for an optional primitive argument. */
internal const val BRIDGE_UNSET = -1

/** Empty blob the bridge reads as an omitted optional byte argument. */
internal val BRIDGE_EMPTY_BYTES = ByteArray(0)

/** Maps an optional flag to the bridge tri-state: unset, false, or true. */
internal fun Boolean?.toBridgeTriState(): Int = when (this) {
    null -> BRIDGE_UNSET
    false -> 0
    true -> 1
}

private const val LOG_TAG = "MeshCoreBridge"

/**
 * Native bridge and in-flight commands for one [MeshCoreSession].
 * Every command delegate shares this instance, so request ids and [close] stay on one table.
 */
internal class MeshCoreSessionHost(
    private val transport: MeshTransport,
    advertisesWriteWithoutResponse: Boolean,
    clientIdentifier: String,
) {
    // This session-lifetime owner creates and closes the native event pump as one unit.
    @Suppress("InjectDispatcher")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nextRequestId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<CommandResult>>()
    private val nativeLock = Any()
    private var closed = false

    // An unbounded ingest channel decouples the Swift upcall thread (which cannot suspend) from
    // collector backpressure, so no device event is dropped in the pipe; a pump re-emits onto the
    // hot flow. A slow collector suspends the pump, not the JNI thread.
    private val ingest = Channel<MeshEvent>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 64)

    /** The hot stream of pushed device events, including connection-state transitions. */
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    /** Number of active [events] collectors; lets a caller await a subscriber before triggering. */
    val eventSubscriberCount: StateFlow<Int> = _events.subscriptionCount

    private val arena: ClosableSwiftArena = SwiftArena.ofConfined()
    val bridge: NativeBridge

    init {
        installAndroidRegionResolver()
        installAndroidRxLogDecryptor()
        val pointer = MeshCoreNative.nativeCreate(Callbacks(), advertisesWriteWithoutResponse, clientIdentifier)
        require(pointer != 0L) { "native MeshCoreBridge construction failed" }
        bridge = NativeBridge.wrapMemoryAddressUnsafe(pointer, arena)
        scope.launch {
            for (event in ingest) {
                _events.emit(event)
            }
        }
    }

    private inner class Callbacks : MeshBridgeNativeCallbacks {
        override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) = transport.sendBytes(bytes, withoutResponse)

        override fun advertisesWriteWithoutResponse(): Boolean =
            (transport as? MeshLink)?.advertisesWriteWithoutResponse ?: false

        override fun supportsPipelinedReads(): Boolean = (transport as? MeshLink)?.supportsPipelinedReads ?: false

        override fun onSuccess(requestID: Long, resultBytes: ByteArray) {
            val deferred = pending[requestID] ?: return
            try {
                deferred.complete(decodeCommandResult(resultBytes.decodeToString()))
            } catch (e: BridgeDecodingException) {
                deferred.completeExceptionally(e)
            }
        }

        override fun onFailure(requestID: Long, errorCode: Int, message: String) {
            pending[requestID]?.completeExceptionally(
                MeshBridgeException(BridgeErrorCode.fromCode(errorCode), message),
            )
        }

        override fun onEvent(eventBytes: ByteArray) {
            val event =
                try {
                    decodeMeshEvent(eventBytes.decodeToString())
                } catch (e: BridgeDecodingException) {
                    Log.w(LOG_TAG, "dropping undecodable event: ${e.message}")
                    null
                }
            if (event != null) ingest.trySend(event)
        }

        override fun onLog(line: String) {
            Log.d(LOG_TAG, line)
        }
    }

    suspend fun await(dispatch: (Long) -> Unit): CommandResult {
        val id = nextRequestId.incrementAndGet()
        return awaitRegistered(
            pending,
            id,
            onCancellation = { requestID ->
                synchronized(nativeLock) {
                    if (!closed) bridge.cancelRequest(requestID)
                }
            },
            dispatch = { requestID ->
                synchronized(nativeLock) {
                    if (closed) throw MeshBridgeException(BridgeErrorCode.NOT_CONNECTED, "session closed")
                    dispatch(requestID)
                }
            },
        )
    }

    fun signalNative(signal: (NativeBridge) -> Unit) = synchronized(nativeLock) {
        if (!closed) signal(bridge)
    }

    suspend fun expectOk(dispatch: (Long) -> Unit) {
        await(dispatch)
    }

    fun close() = synchronized(nativeLock) {
        if (closed) return@synchronized
        closed = true
        pending.keys.forEach { bridge.cancelRequest(it) }
        pending.values.forEach { it.cancel() }
        pending.clear()
        ingest.close()
        scope.cancel()
        arena.close()
    }
}
