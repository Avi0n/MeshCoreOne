package one.meshcore.shared.backup

/** Civil time stamped into a backup filename as `yyyy-MM-dd HHMMSS`. */
data class BackupExportClock(
    val year: Int,
    val month: Int,
    val dayOfMonth: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
)
