package one.meshcore.shared.settings

/**
 * The host and app that produced the export (iOS `LogExportService.generateHeader`). Without this a
 * shared dump cannot say which build or phone a radio drop happened on.
 */
data class PlatformDiagnostics(
    val appVersion: String,
    val buildNumber: String,
    val deviceModel: String,
    val osName: String,
    val osVersion: String,
    val exportedAtEpochMillis: Long,
)
