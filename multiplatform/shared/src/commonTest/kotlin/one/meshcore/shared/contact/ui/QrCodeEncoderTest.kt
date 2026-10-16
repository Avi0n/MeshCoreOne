package one.meshcore.shared.contact.ui

import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.ContactType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [QrCodeEncoder], the production QR encoder over qrcode-kotlin. Verifies it produces a
 * structurally valid QR grid for a real contact-share URI (the interop-frozen payload), that the three
 * finder patterns land in the corners (so [QrMatrix]'s x=column/y=row orientation is correct), and that
 * encoding is deterministic.
 */
class QrCodeEncoderTest {
    private val goldenUri = ContactService.exportContactURI(
        name = "Alice",
        publicKey = ByteArray(PUBLIC_KEY_BYTES) { (it + 1).toByte() },
        type = ContactType.CHAT,
    )

    @Test
    fun encodesAContactUriIntoAValidQrGrid() {
        val matrix = assertNotNull(QrCodeEncoder(goldenUri, QrCorrectionLevel.M))

        // A QR symbol is version v (1..40): side = 21 + 4*(v-1). Assert the size is one of those.
        assertTrue(matrix.size in MIN_QR_SIDE..MAX_QR_SIDE, "unexpected QR side ${matrix.size}")
        assertEquals(0, (matrix.size - MIN_QR_SIDE) % QR_VERSION_STEP, "side is not a valid QR version")
    }

    @Test
    fun placesTheThreeFinderPatternsInTheCorners() {
        val matrix = assertNotNull(QrCodeEncoder(goldenUri, QrCorrectionLevel.M))
        val last = matrix.size - 1

        // Each finder is a 7x7 dark ring with a light separator ring and a 3x3 dark core.
        assertFinder(matrix, originX = 0, originY = 0)
        assertFinder(matrix, originX = matrix.size - FINDER_SIDE, originY = 0)
        assertFinder(matrix, originX = 0, originY = matrix.size - FINDER_SIDE)

        // Sanity that the grid is populated to its extent.
        assertTrue(matrix.isDark(last, 0) || matrix.isDark(0, last))
    }

    @Test
    fun encodingIsDeterministic() {
        val first = assertNotNull(QrCodeEncoder(goldenUri, QrCorrectionLevel.M))
        val second = assertNotNull(QrCodeEncoder(goldenUri, QrCorrectionLevel.M))

        assertEquals(first.size, second.size)
        for (y in 0 until first.size) {
            for (x in 0 until first.size) {
                assertEquals(first.isDark(x, y), second.isDark(x, y), "cell ($x,$y) differs")
            }
        }
    }

    private fun assertFinder(matrix: QrMatrix, originX: Int, originY: Int) {
        // Dark outer corners, light inner ring, dark core — the finder-pattern signature.
        assertTrue(matrix.isDark(originX, originY), "finder outer corner not dark")
        assertTrue(matrix.isDark(originX + FINDER_SIDE - 1, originY), "finder top-right not dark")
        assertTrue(matrix.isDark(originX, originY + FINDER_SIDE - 1), "finder bottom-left not dark")
        assertFalse(matrix.isDark(originX + 1, originY + 1), "finder separator not light")
        assertTrue(matrix.isDark(originX + 3, originY + 3), "finder core not dark")
    }

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val MIN_QR_SIDE = 21
        const val MAX_QR_SIDE = 177
        const val QR_VERSION_STEP = 4
        const val FINDER_SIDE = 7
    }
}
