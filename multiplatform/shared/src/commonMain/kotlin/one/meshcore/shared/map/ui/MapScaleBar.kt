package one.meshcore.shared.map.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.localeUsesMetric
import one.meshcore.shared.map.mapScaleBarModel
import one.meshcore.shared.remotenode.LPPDisplay

/**
 * MapKit-style distance scale in the map corner. Uses the live camera span so it stays in common
 * code instead of the unmaintained MapLibre scale-bar plugin. Inverse surface roles follow the
 * theme so the overlay is not a MapKit white/black chip.
 */
@Composable
fun MapScaleBar(region: MapCameraRegion?, modifier: Modifier = Modifier) {
    val model = region?.let {
        mapScaleBarModel(it, localeUsesMetric(Locale.current.region), LPPDisplay.currentLengthSymbols())
    } ?: return
    Column(
        modifier
            .fillMaxWidth(model.fractionOfViewport)
            .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = SCALE_BAR_SCRIM_ALPHA))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics { contentDescription = model.label },
    ) {
        Text(
            text = model.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(SCALE_BAR_THICKNESS)
                .background(MaterialTheme.colorScheme.inverseOnSurface),
        )
    }
}

private val SCALE_BAR_THICKNESS = 3.dp
private const val SCALE_BAR_SCRIM_ALPHA = 0.92f
