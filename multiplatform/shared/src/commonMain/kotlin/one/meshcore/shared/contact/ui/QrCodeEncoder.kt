package one.meshcore.shared.contact.ui

import qrcode.QRCode
import qrcode.raw.ErrorCorrectionLevel

/**
 * The production [QrEncoder]: encodes a payload into a module grid with qrcode-kotlin (a pure-Kotlin
 * multiplatform QR generator), so the same implementation backs both the Android and desktop QR sheets.
 * The library owns Reed-Solomon and masking; the interop-load-bearing part is the payload, already frozen
 * and golden-tested by the contact/channel URI slices, so any conformant encoder is byte-safe.
 *
 * Only the module grid is read ([QRCode.rawData]); rendering is left to [QrImage] so the QR draws crisp
 * onto the Compose canvas with no interpolation, matching the iOS `interpolation(.none)` render.
 */
val QrCodeEncoder: QrEncoder = { payload, correctionLevel ->
    val qr = QRCode.ofSquares()
        .withErrorCorrectionLevel(correctionLevel.toLibraryLevel())
        .build(payload)
    val raw = qr.rawData
    val size = raw.size
    val modules = BooleanArray(size * size)
    for (column in raw.indices) {
        for (row in raw[column].indices) {
            val square = raw[column][row]
            // Place each module by its own (row, col) so the grid orientation is independent of the
            // library's array-nesting convention; QrMatrix indexes x=column, y=row.
            modules[square.row * size + square.col] = square.dark
        }
    }
    QrMatrix(size, modules)
}

private fun QrCorrectionLevel.toLibraryLevel(): ErrorCorrectionLevel = when (this) {
    QrCorrectionLevel.L -> ErrorCorrectionLevel.LOW
    QrCorrectionLevel.M -> ErrorCorrectionLevel.MEDIUM
    QrCorrectionLevel.Q -> ErrorCorrectionLevel.HIGH
    QrCorrectionLevel.H -> ErrorCorrectionLevel.VERY_HIGH
}
