package one.meshcore.shared.onboarding

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.rememberReduceMotion

private const val PULSE_MIN_ALPHA = 0.55f
private const val PULSE_DURATION_MS = 1000

/**
 * Pair-step hero: Material Sensors standing in for iOS `antenna.radiowaves.left.and.right`.
 * Pulses unless Reduce Motion is on, matching `PulsingAntenna`.
 */
@Composable
internal fun PulsingAntenna(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "pulsingAntenna")
    val pulse by transition.animateFloat(
        initialValue = PULSE_MIN_ALPHA,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(PULSE_DURATION_MS),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "antennaPulse",
    )
    val alpha = if (reduceMotion) 1f else pulse
    Box(
        modifier = modifier.fillMaxWidth().height(HERO_SIZE),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AppIcons.Sensors,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(HERO_ICON_SIZE).graphicsLayer { this.alpha = alpha },
        )
    }
}
