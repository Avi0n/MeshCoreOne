package one.meshcore.shared.settings

/**
 * The connection facts a diagnostics export opens with, mirroring the iOS `LogExportService` connection
 * section. Plain read-outs only, so the formatter stays pure and testable.
 */
data class ConnectionDiagnostics(
    val state: String,
    val intent: String,
    /** The persisted summary of the last way out of a connection; null when none has been recorded. */
    val lastDisconnectDiagnostic: String?,
    val deviceName: String?,
    val deviceId: String?,
    val lastConnectedEpochSeconds: Double?,
    /** iOS `currentBLEDiagnosticsSummary`; null when the caller has no snapshot. */
    val bleSummary: String? = null,
)
