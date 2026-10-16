package one.meshcore.shared.meshcore

import android.util.Log
import one.meshcore.shared.meshcore.jni.MeshCoreNative

private const val LOG_TAG = "RegionResolver"

/**
 * Attributes packets to flood regions through the Swift bridge, the product's one protocol
 * implementation.
 *
 * The call is synchronous: the native side neither waits on the radio nor allocates a session, so
 * the batch comes back on the calling thread rather than through the result listener every other
 * command uses. A boundary failure leaves every row unattributed and is logged rather than thrown —
 * the RX log must keep listing packets when their region cannot be named.
 */
private object BridgeRegionResolver : RegionResolver {
    override fun resolve(regionNames: List<String>, rows: List<RegionResolveRow>): List<RegionResolveHit> = when {
        rows.isEmpty() -> emptyList()
        else -> try {
            decodeRegionResolveResponse(
                MeshCoreNative.nativeResolveRegions(encodeRegionResolveRequest(regionNames, rows)).decodeToString(),
                rows.size,
            )
        } catch (e: BridgeDecodingException) {
            Log.w(LOG_TAG, "leaving ${rows.size} rows unattributed: ${e.message}")
            List(rows.size) { RegionResolveHit.NONE }
        }
    }
}

internal fun installAndroidRegionResolver() {
    PlatformBridge.regionResolver = BridgeRegionResolver
}
