package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid

/** iOS `AppBackupService` / `PersistenceStore` backup dump lines. */
fun backupExportedLine(jsonBytes: Int, compressedBytes: Int): String =
    "Backup exported: $jsonBytes bytes JSON → $compressedBytes bytes compressed"

internal fun backupImportCompleteLine(inserted: Int, merged: Int, skipped: Int): String =
    "Import complete: $inserted inserted, $merged merged, $skipped skipped"

internal fun backupDuplicateDeviceLine(count: Int): String =
    "Backup contained $count duplicate device public key(s); first occurrence wins."

internal fun backupChannelSlotDropLine(slot: Int, radioID: Uuid): String =
    "Backup channel at slot $slot for radio ${radioID.value} has no free local slot; " +
        "dropping it and its messages."
