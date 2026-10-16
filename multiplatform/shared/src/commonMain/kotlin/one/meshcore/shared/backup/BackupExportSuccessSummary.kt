package one.meshcore.shared.backup

/**
 * What Settings shows after a successful file export (iOS `ExportSuccessSummary`).
 * [byteCount] is the compressed `.mc1backup` size, matching Swift `ExportResult.data.count`.
 */
data class BackupExportSuccessSummary(val filename: String, val byteCount: Int, val preview: BackupImportPreview)
