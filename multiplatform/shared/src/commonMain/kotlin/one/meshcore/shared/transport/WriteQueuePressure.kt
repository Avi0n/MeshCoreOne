package one.meshcore.shared.transport

/**
 * Counts BLE outbound backlog the way iOS `claimWriteSlot` counts waiters. A warning fires when
 * [consecutive] queued writes reach [threshold] (iOS `queuePressureThreshold` = 3).
 */
class WriteQueuePressure(
    private val threshold: Int = DEFAULT_THRESHOLD,
    private val onPressure: (depth: Int, consecutive: Int) -> Unit,
) {
    var queued: Int = 0
        private set
    var consecutive: Int = 0
        private set

    fun enqueue() {
        queued += 1
        if (queued > 1) {
            consecutive += 1
            if (consecutive >= threshold) onPressure(queued, consecutive)
        }
    }

    fun dequeue() {
        queued = (queued - 1).coerceAtLeast(0)
        if (queued == 0) consecutive = 0
    }

    companion object {
        const val DEFAULT_THRESHOLD: Int = 3
    }
}
