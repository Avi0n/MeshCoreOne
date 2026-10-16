package one.meshcore.shared.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.connection.BleSignalTier
import one.meshcore.shared.ui.icons.AppIcons

internal val DEVICE_TRANSPORT_GLYPH_SIZE = 24.dp

/** Material `ListItem` leading transport icon (Bluetooth or Wi‑Fi). */
@Composable
internal fun DeviceTransportGlyph(viaWifi: Boolean, modifier: Modifier = Modifier) {
    val tint = if (viaWifi) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    Icon(
        imageVector = if (viaWifi) AppIcons.Wifi else AppIcons.CellTower,
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(DEVICE_TRANSPORT_GLYPH_SIZE),
    )
}

/** Theme roles for BLE scan strength: strong primary, medium tertiary, weak error. */
@Composable
internal fun BleSignalTier.barTint(): Color = when (this) {
    BleSignalTier.STRONG -> MaterialTheme.colorScheme.primary
    BleSignalTier.MEDIUM -> MaterialTheme.colorScheme.tertiary
    BleSignalTier.WEAK -> MaterialTheme.colorScheme.error
}

/** Scan-row strength meter. The row announces strength, so the bars stay visual. */
@Composable
internal fun SignalBars(tier: BleSignalTier, modifier: Modifier = Modifier) {
    SignalBarMeter(
        filledBars = tier.filledBarCount,
        tint = tier.barTint(),
        modifier = modifier.clearAndSetSemantics { },
    )
}
