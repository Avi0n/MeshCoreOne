package one.meshcore.shared.contact

/** Advert and path work released when the final nested contact-sync hold ends. */
internal data class SyncHoldRelease(
    val becameIdle: Boolean,
    val toFetch: List<ByteArray>,
    val pathUpdates: List<ByteArray>,
    val drainedHearTimes: Map<String, Long>,
) {
    companion object {
        fun stillHeld(): SyncHoldRelease = SyncHoldRelease(
            becameIdle = false,
            toFetch = emptyList(),
            pathUpdates = emptyList(),
            drainedHearTimes = emptyMap(),
        )

        fun idleKeepingQueues(): SyncHoldRelease = SyncHoldRelease(
            becameIdle = true,
            toFetch = emptyList(),
            pathUpdates = emptyList(),
            drainedHearTimes = emptyMap(),
        )
    }
}
