package one.meshcore.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageShareMimeTest {
    @Test
    fun sniffsPngGifJpegAndWebp() {
        assertEquals("image/png", sniffImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals("image/gif", sniffImageMime("GIF89a".encodeToByteArray()))
        assertEquals("image/jpeg", sniffImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        val webp = ByteArray(12)
        "RIFF".encodeToByteArray().copyInto(webp, 0)
        "WEBP".encodeToByteArray().copyInto(webp, 8)
        assertEquals("image/webp", sniffImageMime(webp))
        assertNull(sniffImageMime(byteArrayOf(0x00, 0x01)))
    }

    @Test
    fun extensionFollowsMimeThenSniff() {
        assertEquals("png", imageExtension("image/png", byteArrayOf()))
        assertEquals("gif", imageExtension("image/gif", byteArrayOf()))
        assertEquals("jpg", imageExtension("image/jpeg", byteArrayOf()))
        assertEquals("webp", imageExtension("image/webp", byteArrayOf()))
        assertEquals("jpg", imageExtension("application/octet-stream", byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}
