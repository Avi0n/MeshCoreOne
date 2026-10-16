package one.meshcore.shared.backup

/**
 * Failures the backup parse and import gates raise, mirroring the Swift `AppBackupError` cases that
 * `parseBackup` throws. Each case has a matching `settings_backup_error_*` string; the surface that
 * catches one is responsible for the localized copy, so the messages here stay developer-facing.
 */
sealed class AppBackupError(message: String) : Exception(message) {
    /** The payload is not a readable backup (bad DEFLATE stream, or JSON that does not decode). */
    class InvalidFile(cause: String) : AppBackupError("Backup file is invalid or could not be read: $cause")

    /** The compressed file is larger than [MAX_BACKUP_COMPRESSED_BYTES], rejected before decompression. */
    class FileTooLarge(val actualBytes: Int, val maxBytes: Int) :
        AppBackupError("Backup file is $actualBytes bytes; limit is $maxBytes bytes")

    /** The inflater would exceed [MAX_BACKUP_UNCOMPRESSED_BYTES]. */
    class DecompressedTooLarge(val maxBytes: Int) :
        AppBackupError("Backup expands past the safe size limit ($maxBytes bytes uncompressed)")

    /** The envelope declares a format newer than this build understands. */
    class UnsupportedVersion(val found: Int, val maxSupported: Int) :
        AppBackupError("Backup format version $found exceeds the supported version $maxSupported")

    /** The manifest's declared per-family counts disagree with the arrays actually present. */
    class CorruptedManifest : AppBackupError("Backup manifest counts do not match the envelope contents")

    /** A radio connected (or is connecting) after the user started import. */
    class RadioConnected : AppBackupError("Cannot import a backup while a radio is connected")
}
