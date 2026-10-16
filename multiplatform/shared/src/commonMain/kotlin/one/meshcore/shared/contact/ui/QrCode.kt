package one.meshcore.shared.contact.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.min

/**
 * A decoded QR symbol as a square grid of dark/light modules. Framework-neutral so the matrix producer
 * (a QR-encoding library) stays isolated from the Compose renderer: [QrImage] draws crisp rectangles per
 * module and never smooths, matching the iOS `interpolation(.none)` render.
 */
class QrMatrix(val size: Int, private val modules: BooleanArray) {
    init {
        require(modules.size == size * size) { "modules must be size*size" }
    }

    /** True when the module at ([x], [y]) is dark. */
    fun isDark(x: Int, y: Int): Boolean = modules[y * size + x]
}

/**
 * Encodes [payload] into a [QrMatrix] at the given error-correction level, or null when encoding
 * fails. The producer is injected so the shared renderer stays isolated from the platform encoder.
 */
typealias QrEncoder = (payload: String, correctionLevel: QrCorrectionLevel) -> QrMatrix?

/** QR error-correction level. Contact QRs use [M] to match the iOS producer's module pattern. */
enum class QrCorrectionLevel { L, M, Q, H }

/**
 * Renders [payload] as a QR image at [size] with black modules on a white card so a camera can
 * decode it in dark theme. A placeholder is shown when [encode] yields null. Mirrors the iOS
 * `QRCodeSection`: opaque modules, no interpolation, fixed render size.
 */
@Composable
fun QrImage(
    payload: String,
    encode: QrEncoder,
    modifier: Modifier = Modifier,
    size: Dp = QR_RENDER_SIZE,
    foreground: Color = Color.Black,
    correctionLevel: QrCorrectionLevel = QrCorrectionLevel.M,
) {
    val matrix = encode(payload, correctionLevel)
    if (matrix == null) {
        QrUnavailable(modifier = modifier.size(size))
        return
    }
    Surface(
        modifier = modifier.size(size),
        color = Color.White,
    ) {
        Canvas(Modifier.fillMaxSize().padding(QR_QUIET_ZONE)) {
            val modulePx = min(this.size.width, this.size.height) / matrix.size
            for (y in 0 until matrix.size) {
                for (x in 0 until matrix.size) {
                    if (matrix.isDark(x, y)) {
                        drawRect(
                            color = foreground,
                            topLeft = Offset(x * modulePx, y * modulePx),
                            size = Size(modulePx, modulePx),
                        )
                    }
                }
            }
        }
    }
}

/** The placeholder shown while no QR encoder is wired, so the sheet still lays out. */
@Composable
private fun QrUnavailable(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(QR_PLACEHOLDER_GLYPH, style = MaterialTheme.typography.displayMedium)
    }
}

/** Fixed 200 dp render size, matching the iOS `Constants.qrCodeSize`. */
val QR_RENDER_SIZE: Dp = 200.dp

/** Quiet zone so the white card still reads as a QR finder pattern. */
private val QR_QUIET_ZONE = 12.dp

private const val QR_PLACEHOLDER_GLYPH = "▢"
