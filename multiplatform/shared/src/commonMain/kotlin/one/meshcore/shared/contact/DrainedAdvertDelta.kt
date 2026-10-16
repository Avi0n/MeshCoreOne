package one.meshcore.shared.contact

/** Immutable work drained from the advert queues for one delta-sync attempt. */
internal data class DrainedAdvertDelta(
    val advert: List<ByteArray>,
    val path: List<ByteArray>,
    val receiveTimes: Map<String, Long>,
    val fullRefetch: Boolean,
    val unknownAtStart: List<ByteArray>,
    val pathLastModified: Map<String, Long>,
)
