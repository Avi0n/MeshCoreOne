package one.meshcore.shared.backup

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

private const val ZLIB_IO_BUFFER_SIZE = 16 * 1024

/**
 * Apple's `NSData.compressed(using: .zlib)` / `InputFilter(.decompress, using: .zlib)` use **raw
 * DEFLATE** (no RFC 1950 zlib CMF/FLG wrapper). Match that with `nowrap = true`.
 */
actual fun zlibCompress(data: ByteArray): ByteArray {
    // nowrap=true → raw DEFLATE (Apple Foundation .zlib wire form).
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size.coerceAtLeast(ZLIB_IO_BUFFER_SIZE))
        val buf = ByteArray(ZLIB_IO_BUFFER_SIZE)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    } finally {
        deflater.end()
    }
}

actual fun zlibDecompress(data: ByteArray, maxUncompressedBytes: Int): ByteArray {
    // Prefer Apple-compatible raw DEFLATE; fall back to RFC 1950 zlib wrapper for interop.
    return runCatching { inflateOnce(data, maxUncompressedBytes, nowrap = true) }
        .recoverCatching { first ->
            if ((first as? BackupZlibException)?.exceededUncompressedLimit == true) throw first
            inflateOnce(data, maxUncompressedBytes, nowrap = false)
        }
        .getOrElse { cause ->
            throw (cause as? BackupZlibException)
                ?: BackupZlibException("Invalid zlib/deflate backup payload", cause)
        }
}

private fun inflateOnce(data: ByteArray, maxUncompressedBytes: Int, nowrap: Boolean): ByteArray {
    val inflater = Inflater(nowrap)
    try {
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size.coerceAtLeast(ZLIB_IO_BUFFER_SIZE))
        val buf = ByteArray(ZLIB_IO_BUFFER_SIZE)
        while (!inflater.finished()) {
            val n = inflateChunk(inflater, buf) ?: break
            ensureCapacity(out.size(), n, maxUncompressedBytes)
            out.write(buf, 0, n)
        }
        if (!inflater.finished()) {
            throw BackupZlibException("Incomplete zlib/deflate backup payload")
        }
        if (out.size() == 0 && data.isNotEmpty()) {
            throw BackupZlibException("Invalid zlib/deflate backup payload (empty output)")
        }
        return out.toByteArray()
    } finally {
        inflater.end()
    }
}

/** @return bytes written, or null when the inflater needs more input / is stuck. */
private fun inflateChunk(inflater: Inflater, buf: ByteArray): Int? {
    val n = try {
        inflater.inflate(buf)
    } catch (e: DataFormatException) {
        throw BackupZlibException("Invalid zlib/deflate backup payload", e)
    }
    if (n > 0) return n
    return null
}

private fun ensureCapacity(currentSize: Int, nextChunk: Int, maxUncompressedBytes: Int) {
    if (currentSize + nextChunk > maxUncompressedBytes) {
        throw BackupZlibException(
            "Backup exceeds max uncompressed size ($maxUncompressedBytes bytes)",
            exceededUncompressedLimit = true,
        )
    }
}
