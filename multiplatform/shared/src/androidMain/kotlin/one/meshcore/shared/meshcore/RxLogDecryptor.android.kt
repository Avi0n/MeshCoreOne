package one.meshcore.shared.meshcore

import one.meshcore.shared.meshcore.jni.MeshCoreNative

/**
 * Decrypts RX-log rows through the Swift bridge, the product's one protocol implementation.
 *
 * The call is synchronous: the native side neither waits on the radio nor allocates a session, so
 * the batch comes back on the calling thread rather than through the result listener every other
 * command uses.
 */
private object BridgeRxLogDecryptor : RxLogDecryptor {
    override fun decrypt(rows: List<RxLogDecryptRow>): List<RxLogDecryptResult> = when {
        rows.isEmpty() -> emptyList()
        else -> decodeRxLogDecryptResponse(
            MeshCoreNative.nativeDecryptRxLog(encodeRxLogDecryptRequest(rows)).decodeToString(),
            rows.size,
        )
    }
}

internal fun installAndroidRxLogDecryptor() {
    PlatformBridge.rxLogDecryptor = BridgeRxLogDecryptor
}
