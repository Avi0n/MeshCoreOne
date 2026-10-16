package one.meshcore.shared.meshcore.jni

import androidx.annotation.Keep

/**
 * The raw callback surface the Swift bridge upcalls into. `MeshCoreNative.nativeCreate` hands the
 * Swift shim a reference to an implementation of this interface; the shim resolves these five
 * methods by name and JVM signature (`sendBytes([BZ)V`, `advertisesWriteWithoutResponse()Z`,
 * `supportsPipelinedReads()Z`, `onSuccess(J[B)V`, `onFailure`, `onEvent`, `onLog`) once, then
 * calls them from Swift threads. The signatures are therefore a frozen part of
 * the JNI contract — changing a name or type here means changing the Swift shim in lockstep.
 *
 * `@Keep` prevents R8 from renaming the methods in a minified build, which would break the by-name
 * JNI lookup.
 */
@Keep
interface MeshBridgeNativeCallbacks {
    /** MeshCore has an outbound frame to write to the link. */
    fun sendBytes(bytes: ByteArray, withoutResponse: Boolean)

    /**
     * Live GATT Write-Command capability. iOS reads the tx characteristic after discovery;
     * a frozen `true` here would put ESP32 on the nRF52 pipelined channel-read path.
     */
    fun advertisesWriteWithoutResponse(): Boolean

    /**
     * Whether windowed channel reads are viable. BLE follows [advertisesWriteWithoutResponse];
     * TCP opts in even without a write characteristic.
     */
    fun supportsPipelinedReads(): Boolean

    /** The command with this id succeeded; `resultBytes` is a v1 result envelope. */
    fun onSuccess(requestID: Long, resultBytes: ByteArray)

    /** The command with this id failed; `errorCode` is a `BridgeErrorCode` raw value. */
    fun onFailure(requestID: Long, errorCode: Int, message: String)

    /** A pushed device event, as a v1 event envelope. */
    fun onEvent(eventBytes: ByteArray)

    /** A bridge/MeshCore log line, level-prefixed. */
    fun onLog(line: String)
}
