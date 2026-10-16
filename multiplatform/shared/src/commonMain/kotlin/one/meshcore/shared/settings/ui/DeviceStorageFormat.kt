package one.meshcore.shared.settings.ui

import kotlin.math.round

/**
 * Formats firmware storage counts (binary kilobytes) as "used / total", matching iOS
 * `DeviceInfoView.formatStorage` (`ByteCountFormatStyle.memory`, 1024-byte kilobytes).
 */
internal fun formatStorage(usedKB: Long, totalKB: Long): String =
    "${formatMemoryBytes(usedKB * BYTES_PER_KILOBYTE)} / ${formatMemoryBytes(totalKB * BYTES_PER_KILOBYTE)}"

internal fun formatMemoryBytes(bytes: Long): String {
    if (bytes < BYTES_PER_KILOBYTE) return "$bytes bytes"
    var value = bytes.toDouble() / BYTES_PER_KILOBYTE
    var unit = 0
    while (value >= BYTES_PER_KILOBYTE && unit < MEMORY_UNITS.lastIndex) {
        value /= BYTES_PER_KILOBYTE
        unit += 1
    }
    return "${formatMemoryNumber(value)} ${MEMORY_UNITS[unit]}"
}

private fun formatMemoryNumber(value: Double): String {
    val rounded = round(value * MEMORY_DECIMAL_PRECISION) / MEMORY_DECIMAL_PRECISION
    return if (rounded >= WHOLE_MEMORY_UNIT_THRESHOLD || rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}

private const val BYTES_PER_KILOBYTE = 1024L
private const val MEMORY_DECIMAL_PRECISION = 10.0
private const val WHOLE_MEMORY_UNIT_THRESHOLD = 10.0
private val MEMORY_UNITS = arrayOf("KB", "MB", "GB", "TB")
