package one.meshcore.shared.settings.ui

/**
 * iOS `ImportPreviewSheet` mutually exclusive views: importing, success, cancelled, error, then
 * preview. A failed merge must not keep showing the preview under the error.
 */
internal enum class BackupImportSheetPhase {
    NONE,
    IMPORTING,
    SUCCESS,
    CANCELLED,
    FAILED,
    PREVIEW,
}
