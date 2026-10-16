package one.meshcore.shared.meshcore.jni

/**
 * The one hand-rolled JNI entry point that jextract could not generate: construction of a
 * `MeshCoreBridge` from a Kotlin callback object. jextract (JNI mode, swift-java 0.4.2) exports the
 * bridge's listener protocols as Swift-backed instances, so its generated `$init` cannot accept a
 * Kotlin implementation. Every other bridge method (commands and lifecycle) uses the generated
 * `one.meshcore.bridge.MeshCoreBridge` bindings unchanged.
 *
 * Loading `libMeshCoreBridgeKit.so` triggers swift-java's `JNI_OnLoad`, which caches the JVM the
 * Swift shim needs to upcall into [MeshBridgeNativeCallbacks].
 */
internal object MeshCoreNative {
    init {
        System.loadLibrary("MeshCoreBridgeKit")
    }

    /**
     * Constructs a bridge whose four listeners forward to [callbacks], returning the Swift
     * `UnsafeMutablePointer<MeshCoreBridge>` as a jlong (0 on failure). The pointer is wrapped by
     * `MeshCoreBridge.wrapMemoryAddressUnsafe` in a confined `SwiftArena` so its lifetime is
     * deterministic.
     */
    external fun nativeCreate(
        callbacks: MeshBridgeNativeCallbacks,
        advertisesWriteWithoutResponse: Boolean,
        clientIdentifier: String,
    ): Long

    /**
     * Decrypts a batch of RX-log rows, taking and returning a versioned envelope. This is the one
     * bridge call that answers on the calling thread: it is a pure function of the request bytes, so
     * it waits on neither the radio nor a session.
     */
    external fun nativeDecryptRxLog(request: ByteArray): ByteArray

    /**
     * Attributes a batch of received packets to their flood regions, taking and returning a
     * versioned envelope. Synchronous for the same reason [nativeDecryptRxLog] is: matching is a
     * pure function of the request bytes.
     */
    external fun nativeResolveRegions(request: ByteArray): ByteArray
}
