package one.meshcore.shared.settings

import kotlin.math.round

/**
 * Formats debug-log rows for the diagnostics share sheet. Emits each stored message as iOS
 * `LogExportService.generateLogsSection` does. CLI passwords are redacted at write time by
 * [CommandAuditLogger], not here — re-running [LogRedaction.cliCommand] would also truncate every
 * long Connection/BLE line to 40 characters.
 */
object DebugLogExport {
    private const val EXPORT_TITLE = "=== MeshCore One Debug Export ==="
    private const val CONNECTION_HEADER = "=== Connection ==="
    private const val LOGS_HEADER = "=== Logs (Last 7 Days) ==="
    private const val EMPTY_LOGS = "(No logs found)"
    private const val NO_DIAGNOSTIC =
        "Unavailable (no disconnect callback captured; app may have been suspended)"

    /** How much of a device row id the export carries. The full identifier never ships. */
    private const val DEVICE_ID_PREFIX_LENGTH = 8

    /**
     * Builds shareable text from [entries], opening with the iOS header then [connection] when the
     * caller has it. Empty stores still produce a non-blank header so the export path is never a silent
     * failure when no runtime writers have recorded yet. [connection] carries no default: an export that
     * cannot say what the connection was doing is exactly the one a responder sends back after a radio
     * drops, so a call site has to state the omission rather than fall into it.
     */
    fun format(
        entries: List<DebugLogEntryDto>,
        connection: ConnectionDiagnostics?,
        device: DeviceDiagnostics? = null,
        battery: BatteryDiagnostics? = null,
        platform: PlatformDiagnostics? = null,
    ): String {
        val sections = listOfNotNull(
            headerSection(platform),
            connection?.let(::connectionSection),
            device?.let(::deviceSection),
            battery?.let(::batterySection),
            logsSection(entries),
        )
        return sections.joinToString(separator = "\n\n")
    }

    private fun headerSection(platform: PlatformDiagnostics?): String {
        val lines = mutableListOf(EXPORT_TITLE)
        if (platform != null) {
            lines += "Exported: ${formatUtcIso8601(platform.exportedAtEpochMillis / MILLIS_PER_SECOND)}"
            lines += "App Version: ${platform.appVersion} (${platform.buildNumber})"
            lines += "Device: ${platform.deviceModel}, ${platform.osName} ${platform.osVersion}"
        }
        return lines.joinToString(separator = "\n")
    }

    private fun connectionSection(connection: ConnectionDiagnostics): String {
        val lines = mutableListOf(
            CONNECTION_HEADER,
            "State: ${connection.state}",
            "Intent: ${connection.intent}",
            "Last Disconnect Diagnostic: ${connection.lastDisconnectDiagnostic ?: NO_DIAGNOSTIC}",
        )
        val name = connection.deviceName
        val id = connection.deviceId
        if (name != null || id != null) {
            val abbreviated = id?.take(DEVICE_ID_PREFIX_LENGTH)?.let { " ($it...)" }.orEmpty()
            lines += "Device: ${name.orEmpty()}$abbreviated"
        }
        connection.lastConnectedEpochSeconds?.let { seconds ->
            lines += "Last Connected: ${formatUtcIso8601(round(seconds).toLong())}"
        }
        connection.bleSummary?.let { lines += it }
        return lines.joinToString(separator = "\n")
    }

    private fun deviceSection(device: DeviceDiagnostics): String {
        val header = if (device.isConnected) "=== Device Info ===" else "=== Device Info (Last Connected) ==="
        val mhz = formatFixed(device.frequencyKHz / KHZ_PER_MHZ, DEVICE_FREQ_FRACTION_DIGITS)
        return listOf(
            header,
            "Name: ${device.name}",
            "Firmware: ${device.firmwareVersionString} (v${device.firmwareVersion})",
            "Manufacturer: ${device.manufacturerName}",
            "Build Date: ${device.buildDate}",
            "Radio: $mhz MHz, BW ${device.bandwidthHz} kHz, SF${device.spreadingFactor}, CR${device.codingRate}",
            "TX Power: ${device.txPower} dBm (max ${device.maxTxPower})",
            "Max Nodes: ${device.maxContacts}",
            "Max Channels: ${device.maxChannels}",
            "Manual Add Nodes: ${device.manualAddContacts}",
            "Multi-ACKs: ${device.multiAcks}",
        ).joinToString(separator = "\n")
    }

    private fun batterySection(battery: BatteryDiagnostics): String = listOf(
        "=== Battery ===",
        "Level: ${battery.percentage}%",
        "Voltage: ${formatFixed(battery.voltage, BATTERY_VOLT_FRACTION_DIGITS)} V",
        "Raw: ${battery.millivolts} mV",
    ).joinToString(separator = "\n")

    private fun logsSection(entries: List<DebugLogEntryDto>): String {
        val lines = mutableListOf(LOGS_HEADER)
        if (entries.isEmpty()) {
            lines += EMPTY_LOGS
        } else {
            for (entry in entries) {
                lines +=
                    "${formatUtcLogTimestamp(
                        entry.timestamp,
                    )} [${entry.level.label}] ${entry.category}: ${entry.message}"
            }
            lines += ""
            lines += "Total entries: ${entries.size}"
        }
        return lines.joinToString(separator = "\n")
    }

    private const val KHZ_PER_MHZ = 1000.0
    private const val DEVICE_FREQ_FRACTION_DIGITS = 3
    private const val BATTERY_VOLT_FRACTION_DIGITS = 2
    private const val MILLIS_PER_SECOND = 1000L
}

/**
 * iOS `DateFormatter` `yyyy-MM-dd HH:mm:ss.SSS` for a log line, rendered in UTC so a shared dump does
 * not depend on the phone's zone.
 */
internal fun formatUtcLogTimestamp(epochMillis: Long): String {
    val millis = epochMillis.coerceAtLeast(0L)
    val (date, time) = utcDateAndTime(millis / MILLIS_PER_SECOND)
    val frac = (millis % MILLIS_PER_SECOND).toString().padStart(MILLIS_DIGITS, '0')
    return "$date $time.$frac"
}

/** iOS `ISO8601DateFormatter` with `.withInternetDateTime`, always UTC (`…Z`). */
internal fun formatUtcIso8601(epochSeconds: Long): String {
    val (date, time) = utcDateAndTime(epochSeconds.coerceAtLeast(0L))
    return "${date}T${time}Z"
}

internal fun formatFixed(value: Double, fractionDigits: Int): String {
    val scale = scaleTen(fractionDigits)
    val scaled = round(value * scale).toLong()
    val whole = scaled / scale
    val frac = kotlin.math.abs(scaled % scale)
    return "$whole.${frac.toString().padStart(fractionDigits, '0')}"
}

private fun utcDateAndTime(totalSeconds: Long): Pair<String, String> {
    val days = totalSeconds / SECONDS_PER_DAY
    val sod = (totalSeconds % SECONDS_PER_DAY).toInt()
    val hour = sod / SECONDS_PER_HOUR
    val minute = (sod % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val second = sod % SECONDS_PER_MINUTE
    val (year, month, day) = civilFromUnixDays(days)
    val date = "${pad4(year)}-${pad2(month)}-${pad2(day)}"
    val time = "${pad2(hour)}:${pad2(minute)}:${pad2(second)}"
    return date to time
}

/** Civil date from days since 1970-01-01, UTC. */
private fun civilFromUnixDays(unixDays: Long): Triple<Int, Int, Int> {
    var remaining = unixDays
    var year = UNIX_EPOCH_YEAR
    while (remaining >= daysInYear(year)) {
        remaining -= daysInYear(year)
        year++
    }
    while (remaining < 0) {
        year--
        remaining += daysInYear(year)
    }
    val months = if (isGregorianLeap(year)) DAYS_IN_MONTH_LEAP else DAYS_IN_MONTH_COMMON
    var month = 1
    for (dim in months) {
        if (remaining < dim) break
        remaining -= dim
        month++
    }
    return Triple(year, month, remaining.toInt() + 1)
}

private fun daysInYear(year: Int): Long = if (isGregorianLeap(year)) DAYS_IN_LEAP_YEAR else DAYS_IN_COMMON_YEAR

private fun isGregorianLeap(year: Int): Boolean = year % LEAP_YEAR_INTERVAL == 0 &&
    (year % CENTURY_INTERVAL != 0 || year % GREGORIAN_CYCLE_YEARS == 0)

private fun pad2(value: Int): String = value.toString().padStart(2, '0')

private fun pad4(value: Int): String = value.toString().padStart(YEAR_DIGITS, '0')

private fun scaleTen(digits: Int): Long {
    var scale = 1L
    repeat(digits) { scale *= DECIMAL_BASE }
    return scale
}

private const val MILLIS_PER_SECOND = 1000L
private const val MILLIS_DIGITS = 3
private const val DAYS_IN_COMMON_YEAR = 365L
private const val DAYS_IN_LEAP_YEAR = 366L
private const val LEAP_YEAR_INTERVAL = 4
private const val CENTURY_INTERVAL = 100
private const val GREGORIAN_CYCLE_YEARS = 400
private const val YEAR_DIGITS = 4
private const val DECIMAL_BASE = 10L
private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
private const val SECONDS_PER_DAY = 86_400L
private const val UNIX_EPOCH_YEAR = 1970
private val DAYS_IN_MONTH_COMMON = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
private val DAYS_IN_MONTH_LEAP = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
