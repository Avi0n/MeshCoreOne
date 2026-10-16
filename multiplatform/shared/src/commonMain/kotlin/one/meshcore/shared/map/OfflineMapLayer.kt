package one.meshcore.shared.map

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The layer an offline pack covers, and the style-gating axis [MapStyleSelection] resolves to. Mirrors
 * iOS `OfflineMapLayer`. Raw values are pinned to the iOS case names so persisted pack metadata decodes
 * unchanged. Download zoom and style resources are supplied by [maxDownloadZoom] and
 * [styleUrlForOffline].
 */
@Serializable
enum class OfflineMapLayer(val rawValue: String) {
    @SerialName("base")
    BASE("base"),

    @SerialName("topo")
    TOPO("topo"),
}
