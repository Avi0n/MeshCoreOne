package one.meshcore.shared.backup

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_backup_export_default_filename
import org.jetbrains.compose.resources.getString

/** Builds the post-export sheet model from the shared JSON and the bytes that were written. */
fun backupExportSuccessSummary(
    filename: String,
    compressedBytes: ByteArray,
    jsonText: String,
): BackupExportSuccessSummary = BackupExportSuccessSummary(
    filename = filename,
    byteCount = compressedBytes.size,
    preview = parseBackupImportPreview(jsonText),
)

/**
 * File-size label for the export success hero. Locale-independent so a test can pin the text.
 * Units step at 1024, matching a typical system byte-count style.
 */
fun formatBackupByteCount(byteCount: Int): String {
    val bytes = byteCount.toLong().coerceAtLeast(0L)
    return when {
        bytes < BYTES_PER_KILOBYTE -> "$bytes B"
        bytes < BYTES_PER_MEGABYTE -> oneDecimal(bytes, BYTES_PER_KILOBYTE) + " KB"
        else -> oneDecimal(bytes, BYTES_PER_MEGABYTE) + " MB"
    }
}

private fun oneDecimal(bytes: Long, unit: Long): String {
    val tenths = (bytes * TENTHS_PER_UNIT) / unit
    return "${tenths / TENTHS_PER_UNIT}.${tenths % TENTHS_PER_UNIT}"
}

/** On-disk suffix for a zlib-compressed app backup, matching iOS / Android `.mc1backup`. */
const val BACKUP_FILE_EXTENSION: String = "mc1backup"

const val ENGLISH_BACKUP_FILENAME_TEMPLATE: String = "MC1 Backup %s.mc1backup"

/**
 * Export basename from [template] and a stamp `yyyy-MM-dd HHMMSS`.
 * The default template is the English `settings.backup.export.default_filename` value.
 */
fun suggestedBackupFilename(timestamp: String, template: String = ENGLISH_BACKUP_FILENAME_TEMPLATE): String {
    val withPositional = template.replace("%1\$s", timestamp)
    return withPositional.replace("%s", timestamp)
}

/** Localized export basename for [clock], from `settings.backup.export.default_filename`. */
suspend fun suggestedBackupFilename(clock: BackupExportClock): String = suggestedBackupFilename(
    backupExportTimestamp(clock),
    getString(Res.string.settings_backup_export_default_filename),
)

/**
 * Timestamp fragment iOS `Date.FormatStyle.iso8601` uses for backup names: dashed calendar date,
 * space, then hour/minute/second with no colons.
 */
fun backupExportTimestamp(clock: BackupExportClock): String {
    val mm = clock.month.toString().padStart(DATE_FIELD_PAD, '0')
    val dd = clock.dayOfMonth.toString().padStart(DATE_FIELD_PAD, '0')
    val hh = clock.hour.toString().padStart(DATE_FIELD_PAD, '0')
    val min = clock.minute.toString().padStart(DATE_FIELD_PAD, '0')
    val ss = clock.second.toString().padStart(DATE_FIELD_PAD, '0')
    return "${clock.year}-$mm-$dd $hh$min$ss"
}

/**
 * Ensures [name] ends with `.mc1backup` so a save dialog that strips the suffix still writes the
 * product file type. Blank names fall back to a suffix-only basename.
 */
fun withBackupFileExtension(name: String): String {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "MC1-backup.$BACKUP_FILE_EXTENSION"
        trimmed.endsWith(".$BACKUP_FILE_EXTENSION", ignoreCase = true) -> trimmed
        else -> "$trimmed.$BACKUP_FILE_EXTENSION"
    }
}

private const val BYTES_PER_KILOBYTE = 1_024L
private const val BYTES_PER_MEGABYTE = 1_024L * 1_024L
private const val TENTHS_PER_UNIT = 10L
private const val DATE_FIELD_PAD = 2
