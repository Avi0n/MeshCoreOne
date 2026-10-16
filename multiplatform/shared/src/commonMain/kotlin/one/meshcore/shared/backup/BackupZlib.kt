package one.meshcore.shared.backup

/**
 * On-disk `.mc1backup` transport: Apple Foundation labels this `.zlib`, but the wire form is
 * **raw DEFLATE** (no RFC 1950 CMF/FLG header) — matching `NSData.compressed(using: .zlib)`.
 * The JSON envelope remains the cross-platform contract; compression is transport only.
 */

/** Upper bound on decompressed payload size (512 MiB), matching Swift `maxBackupUncompressedBytes`. */
const val MAX_BACKUP_UNCOMPRESSED_BYTES: Int = 512 * 1_048_576

/**
 * Upper bound on the compressed file accepted for import (50 MiB), matching Swift
 * `maxBackupCompressedBytes`. Checked before decompression so a clearly oversized file never reaches
 * the inflater.
 */
const val MAX_BACKUP_COMPRESSED_BYTES: Int = 50 * 1_048_576

/** Compress [data] for a `.mc1backup` body (Apple-compatible raw DEFLATE). */
expect fun zlibCompress(data: ByteArray): ByteArray

/**
 * Stream-decompress a `.mc1backup` body (raw DEFLATE primary; RFC 1950 zlib accepted as fallback),
 * aborting once output would exceed [maxUncompressedBytes].
 * @throws BackupZlibException when the stream is invalid or exceeds the cap.
 */
expect fun zlibDecompress(data: ByteArray, maxUncompressedBytes: Int = MAX_BACKUP_UNCOMPRESSED_BYTES): ByteArray

/** Thrown when compress/decompress fails or the uncompressed size cap is exceeded. */
class BackupZlibException(message: String, cause: Throwable? = null, val exceededUncompressedLimit: Boolean = false) :
    Exception(message, cause)

/**
 * Encodes [envelope] to compact sorted JSON, then compresses for a `.mc1backup` file body.
 */
fun encodeBackupFileBytes(envelope: AppBackupEnvelope): ByteArray {
    val json = encodeBackupEnvelope(envelope)
    return zlibCompress(json.encodeToByteArray())
}

/**
 * Decodes a product-path backup file: compressed DEFLATE/JSON (primary), or raw JSON UTF-8
 * (secondary paste/debug path when the first byte is `{`).
 */
fun decodeBackupFileBytes(
    bytes: ByteArray,
    maxUncompressedBytes: Int = MAX_BACKUP_UNCOMPRESSED_BYTES,
    maxCompressedBytes: Int = MAX_BACKUP_COMPRESSED_BYTES,
): AppBackupEnvelope {
    val jsonText = backupFileBytesToJsonText(bytes, maxUncompressedBytes, maxCompressedBytes)
    return decodeBackupEnvelope(jsonText)
}

/**
 * Decompresses, decodes and validates a backup file, mirroring Swift `parseBackup`: the compressed
 * size cap runs before the inflater, then the format-version gate and the manifest integrity check.
 * This is the entry point a product import path should use; [decodeBackupFileBytes] stays the
 * validation-free decode for tests and previews. The two caps are parameters so a test can exercise
 * them without allocating the production limits.
 */
fun parseBackupFile(
    bytes: ByteArray,
    maxUncompressedBytes: Int = MAX_BACKUP_UNCOMPRESSED_BYTES,
    maxCompressedBytes: Int = MAX_BACKUP_COMPRESSED_BYTES,
): AppBackupEnvelope {
    val envelope = decodeBackupFileBytes(bytes, maxUncompressedBytes, maxCompressedBytes)
    envelope.validateForImport()
    return envelope
}

/**
 * JSON text for the import preview. A body that is neither JSON nor raw DEFLATE throws
 * [AppBackupError.InvalidFile], matching Swift `parseBackup`.
 */
fun backupFileBytesToJsonText(
    bytes: ByteArray,
    maxUncompressedBytes: Int = MAX_BACKUP_UNCOMPRESSED_BYTES,
    maxCompressedBytes: Int = MAX_BACKUP_COMPRESSED_BYTES,
): String {
    rejectOversizedOrEmptyBackup(bytes, maxCompressedBytes)
    if (bytes[0] == JSON_OBJECT_START) {
        return bytes.decodeToString()
    }
    return decompressBackupToJsonText(bytes, maxUncompressedBytes)
}

private fun rejectOversizedOrEmptyBackup(bytes: ByteArray, maxCompressedBytes: Int) {
    val error = when {
        bytes.isEmpty() -> AppBackupError.InvalidFile("empty")
        bytes.size > maxCompressedBytes ->
            AppBackupError.FileTooLarge(actualBytes = bytes.size, maxBytes = maxCompressedBytes)
        else -> null
    } ?: return
    throw error
}

private fun decompressBackupToJsonText(bytes: ByteArray, maxUncompressedBytes: Int): String = try {
    zlibDecompress(bytes, maxUncompressedBytes).decodeToString()
} catch (e: BackupZlibException) {
    throw backupTextFailure(e, maxUncompressedBytes)
}

private fun backupTextFailure(error: BackupZlibException, maxUncompressedBytes: Int): AppBackupError =
    if (error.exceededUncompressedLimit) {
        AppBackupError.DecompressedTooLarge(maxUncompressedBytes)
    } else {
        AppBackupError.InvalidFile(error.message ?: "unrecognized format")
    }

private const val JSON_OBJECT_START: Byte = 0x7B // '{'
