package one.meshcore.shared.notification

/**
 * The battery-threshold check/seed/reset seam a per-connection battery-readout collector drives, the
 * Kotlin port of iOS `BatteryMonitor`'s threshold bookkeeping. [initializeBatteryThreshold] seeds the
 * notified-threshold set from the first reading on a fresh connection (and notifies once if already
 * below a threshold); [checkBatteryThreshold] is driven by every subsequent reading; [clearBatteryThresholds]
 * resets on disconnect so a future connection re-evaluates from scratch.
 */
interface BatteryNotifier {
    suspend fun initializeBatteryThreshold(deviceName: String, percentage: Int?, isBatteryPresent: Boolean)

    suspend fun checkBatteryThreshold(deviceName: String, percentage: Int, isBatteryPresent: Boolean)

    /**
     * Foreground catch-up: one notification for every threshold crossed while backgrounded.
     * iOS `BatteryMonitor.checkMissedBatteryThreshold`.
     */
    suspend fun checkMissedBatteryThreshold(deviceName: String, percentage: Int, isBatteryPresent: Boolean)

    fun clearBatteryThresholds()

    object NoOp : BatteryNotifier {
        override suspend fun initializeBatteryThreshold(
            deviceName: String,
            percentage: Int?,
            isBatteryPresent: Boolean,
        ) = Unit
        override suspend fun checkBatteryThreshold(deviceName: String, percentage: Int, isBatteryPresent: Boolean) =
            Unit
        override suspend fun checkMissedBatteryThreshold(
            deviceName: String,
            percentage: Int,
            isBatteryPresent: Boolean,
        ) = Unit
        override fun clearBatteryThresholds() = Unit
    }
}
