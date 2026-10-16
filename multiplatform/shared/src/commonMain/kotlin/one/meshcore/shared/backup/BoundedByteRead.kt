package one.meshcore.shared.backup

/** Bytes pulled per read while a document picker is enforcing its cap. */
const val BOUNDED_READ_CHUNK_BYTES: Int = 8 * 1024

/**
 * Reads [read] until EOF or until the next chunk would make the buffer larger than [maxBytes].
 * The oversized chunk is not appended. [read] returns the count placed in the buffer, or a
 * non-positive count at EOF.
 */
fun readBounded(read: (ByteArray) -> Int, maxBytes: Int, chunkSize: Int = BOUNDED_READ_CHUNK_BYTES): ByteArray {
    require(maxBytes >= 0) { "maxBytes" }
    require(chunkSize > 0) { "chunkSize" }
    val chunk = ByteArray(chunkSize)
    var storage = ByteArray(0)
    var total = 0
    while (true) {
        val count = read(chunk)
        if (count <= 0) break
        check(count <= chunk.size) { "reader returned $count for a ${chunk.size}-byte buffer" }
        if (count > maxBytes - total) {
            val reached = if (count > Int.MAX_VALUE - total) Int.MAX_VALUE else total + count
            throw AppBackupError.FileTooLarge(actualBytes = reached, maxBytes = maxBytes)
        }
        if (total + count > storage.size) {
            storage = storage.copyOf(nextStorageSize(storage.size, total + count))
        }
        chunk.copyInto(storage, destinationOffset = total, endIndex = count)
        total += count
    }
    return storage.copyOf(total)
}

/**
 * Product backup import read. The compressed-size cap is applied while the bytes are pulled,
 * then [backupFileBytesToJsonText] repeats it before decompression.
 */
fun readBackupDocument(
    read: (ByteArray) -> Int,
    maxBytes: Int = MAX_BACKUP_COMPRESSED_BYTES,
    chunkSize: Int = BOUNDED_READ_CHUNK_BYTES,
): String {
    val bytes = readBounded(read, maxBytes, chunkSize)
    return backupFileBytesToJsonText(bytes, maxCompressedBytes = maxBytes)
}

/**
 * Product text-document read (node configuration). Uses the same ceiling as a backup file so a
 * content URI cannot be buffered without a bound. A real node configuration is far smaller.
 */
fun readTextDocument(
    read: (ByteArray) -> Int,
    maxBytes: Int = MAX_BACKUP_COMPRESSED_BYTES,
    chunkSize: Int = BOUNDED_READ_CHUNK_BYTES,
): String = readBounded(read, maxBytes, chunkSize).decodeToString()

private fun nextStorageSize(current: Int, needed: Int): Int {
    var size = if (current == 0) BOUNDED_READ_CHUNK_BYTES else current
    while (size < needed) {
        size = if (size > Int.MAX_VALUE / 2) needed else size * 2
    }
    return size
}
