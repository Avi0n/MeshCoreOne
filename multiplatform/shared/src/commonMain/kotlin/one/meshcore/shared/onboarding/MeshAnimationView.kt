package one.meshcore.shared.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.onboarding_mesh_animation_accessibility_label
import one.meshcore.shared.ui.rememberReduceMotion
import org.jetbrains.compose.resources.stringResource

/**
 * Welcome-screen mesh: pulsing nodes, fading links, and a packet traveling 3 → 4 → 1 → 2.
 * Reduce Motion freezes the pose at t=0, matching iOS `MeshAnimationView`.
 */
@Composable
internal fun MeshAnimationView(modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    var timeSeconds by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            timeSeconds = 0.0
            return@LaunchedEffect
        }
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                timeSeconds = (now - startNanos) / NANOS_PER_SECOND
            }
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val label = stringResource(Res.string.onboarding_mesh_animation_accessibility_label)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(MeshAnimationSpec.HEIGHT_DP.dp)
            .semantics {
                contentDescription = label
                role = Role.Image
            },
    ) {
        val nodeRadius = MeshAnimationSpec.NODE_RADIUS_DP.dp.toPx()
        val userRadius = MeshAnimationSpec.USER_NODE_RADIUS_DP.dp.toPx()
        val messageRadius = MeshAnimationSpec.MESSAGE_RADIUS_DP.dp.toPx()
        val stroke = MeshAnimationSpec.EDGE_STROKE_DP.dp.toPx()
        val t = timeSeconds

        MeshAnimationSpec.edges.forEachIndexed { index, edge ->
            val from = MeshAnimationSpec.nodes[edge.from].toOffset(size.width, size.height)
            val to = MeshAnimationSpec.nodes[edge.to].toOffset(size.width, size.height)
            val opacity = meshEdgeOpacity(index, t).toFloat().coerceIn(0f, 1f)
            drawLine(
                color = accent.copy(alpha = opacity),
                start = from,
                end = to,
                strokeWidth = stroke,
            )
        }

        MeshAnimationSpec.nodes.forEachIndexed { index, node ->
            val scale = meshNodeScale(index, t)
            val radius = (if (node.isUser) userRadius else nodeRadius) * scale.toFloat()
            drawCircle(
                color = accent.copy(alpha = if (node.isUser) 1f else 0.7f),
                radius = radius,
                center = node.toOffset(size.width, size.height),
            )
        }

        meshMessagePosition(t)?.let { sample ->
            drawCircle(
                color = accent,
                radius = messageRadius,
                center = Offset(
                    (sample.nx * size.width).toFloat(),
                    (sample.ny * size.height).toFloat(),
                ),
            )
        }
    }
}

private fun MeshAnimNode.toOffset(width: Float, height: Float): Offset =
    Offset((nx * width).toFloat(), (ny * height).toFloat())

private const val NANOS_PER_SECOND = 1_000_000_000.0
