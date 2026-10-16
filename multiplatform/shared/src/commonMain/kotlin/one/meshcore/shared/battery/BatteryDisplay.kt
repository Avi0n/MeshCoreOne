package one.meshcore.shared.battery

import one.meshcore.shared.meshcore.BatteryInfo
import kotlin.math.roundToInt

/**
 * Pure formatting of a [BatteryInfo] for the top-bar indicator and the BLE status menu, kept out of the
 * composables so it is unit-testable on the desktop JVM.
 *
 * `BatteryInfo.level` is a millivolt reading, not a percentage; percentage is derived from it through the
 * connected device's OCV curve, mirroring iOS `BatteryInfo.percentage(using:)`. Callers pass the device's
 * active OCV array so the same conversion drives the indicator, the BLE menu, the foreground notification,
 * and the low-battery threshold monitor.
 */
object BatteryDisplay {
    private const val MIN_PERCENT = 0
    private const val MAX_PERCENT = 100
    private const val MIN_MILLIVOLTS = 0
    private const val MILLIVOLTS_PER_VOLT = 1000.0
    private const val VOLT_DECIMAL_SCALE = 100
    private const val NO_BATTERY_LEVEL = 0

    /** The battery percentage derived from the millivolt reading through [ocvArray]. */
    fun percent(info: BatteryInfo, ocvArray: List<Int>): Int = BatteryOcv.percentage(info, ocvArray)

    /** Whether [info] reflects real battery hardware; a `0` level is a mains-powered device with no ADC pin. */
    fun isBatteryPresent(info: BatteryInfo): Boolean = info.level != NO_BATTERY_LEVEL

    /** The battery percentage as a display string, e.g. `"84%"`. */
    fun percentText(info: BatteryInfo, ocvArray: List<Int>): String = "${percent(info, ocvArray)}%"

    /**
     * The raw battery level (millivolts) as a display voltage string, e.g. `"3.70v"`. Unlike [percent],
     * this reading needs no OCV curve: it is a direct unit conversion of the same millivolt reading
     * `BatteryInfo.level` carries, mirroring the iOS `BatteryInfo.voltage` display extension.
     */
    fun voltageText(info: BatteryInfo): String {
        val volts = info.level.coerceAtLeast(MIN_MILLIVOLTS) / MILLIVOLTS_PER_VOLT
        val hundredths = (volts * VOLT_DECIMAL_SCALE).roundToInt()
        return "${hundredths / VOLT_DECIMAL_SCALE}.${(hundredths % VOLT_DECIMAL_SCALE).toString().padStart(2, '0')}v"
    }

    /** Storage used as a percentage of total, or `null` when the readout omits storage figures. */
    fun storageUsedPercent(info: BatteryInfo): Int? {
        val used = info.usedStorageKB ?: return null
        val total = info.totalStorageKB ?: return null
        if (total <= 0) return null
        return ((used.toDouble() / total.toDouble()) * MAX_PERCENT)
            .toInt()
            .coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    /**
     * Which of the four battery glyph tiers the level falls in, so the indicator can pick an icon/tint
     * without the composable re-deriving thresholds. Mirrors the warning thresholds [20, 10].
     */
    fun tier(info: BatteryInfo, ocvArray: List<Int>): BatteryTier {
        val pct = percent(info, ocvArray)
        return when {
            pct <= CRITICAL_TIER_MAX -> BatteryTier.CRITICAL
            pct <= LOW_TIER_MAX -> BatteryTier.LOW
            pct <= MEDIUM_TIER_MAX -> BatteryTier.MEDIUM
            else -> BatteryTier.FULL
        }
    }

    private const val CRITICAL_TIER_MAX = 10
    private const val LOW_TIER_MAX = 20
    private const val MEDIUM_TIER_MAX = 50
}

/** A coarse battery level band the indicator maps to an icon and tint. */
enum class BatteryTier {
    CRITICAL,
    LOW,
    MEDIUM,
    FULL,
}
