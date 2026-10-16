package one.meshcore.shared.onboarding

import kotlin.math.min
import kotlin.math.sin

/** Layout and timing for the welcome mesh visualization, matching iOS `MeshAnimationView`. */
internal object MeshAnimationSpec {
    const val HEIGHT_DP = 150
    const val NODE_RADIUS_DP = 8f
    const val USER_NODE_RADIUS_DP = 12f
    const val MESSAGE_RADIUS_DP = 4f
    const val EDGE_STROKE_DP = 1.5f

    const val EDGE_FADE_FREQUENCY = 0.8
    const val EDGE_MIN_OPACITY = 0.3
    const val EDGE_FADE_AMPLITUDE = 0.3
    const val EDGE_PHASE_OFFSET = 0.7

    const val NODE_PULSE_FREQUENCY = 1.2
    const val NODE_PULSE_AMPLITUDE = 0.15
    const val NODE_PHASE_OFFSET = 0.5

    const val MESSAGE_CYCLE_SECONDS = 4.0

    val nodes: List<MeshAnimNode> = listOf(
        MeshAnimNode(0.2, 0.3, isUser = false),
        MeshAnimNode(0.5, 0.15, isUser = false),
        MeshAnimNode(0.8, 0.25, isUser = false),
        MeshAnimNode(0.15, 0.7, isUser = false),
        MeshAnimNode(0.5, 0.55, isUser = true),
        MeshAnimNode(0.85, 0.75, isUser = false),
    )

    val edges: List<MeshAnimEdge> = listOf(
        MeshAnimEdge(0, 1),
        MeshAnimEdge(1, 2),
        MeshAnimEdge(0, 4),
        MeshAnimEdge(1, 4),
        MeshAnimEdge(3, 4),
        MeshAnimEdge(4, 5),
        MeshAnimEdge(2, 5),
    )

    val messagePath: List<Int> = listOf(3, 4, 1, 2)
}

internal data class MeshAnimNode(val nx: Double, val ny: Double, val isUser: Boolean)

internal data class MeshAnimEdge(val from: Int, val to: Int)

internal data class MeshMessageSample(val nx: Double, val ny: Double)

internal fun meshEdgeOpacity(index: Int, timeSeconds: Double): Double = MeshAnimationSpec.EDGE_MIN_OPACITY +
    MeshAnimationSpec.EDGE_FADE_AMPLITUDE *
    sin(timeSeconds * MeshAnimationSpec.EDGE_FADE_FREQUENCY + index * MeshAnimationSpec.EDGE_PHASE_OFFSET)

internal fun meshNodeScale(index: Int, timeSeconds: Double): Double = 1.0 + MeshAnimationSpec.NODE_PULSE_AMPLITUDE *
    sin(timeSeconds * MeshAnimationSpec.NODE_PULSE_FREQUENCY + index * MeshAnimationSpec.NODE_PHASE_OFFSET)

internal fun meshMessagePosition(timeSeconds: Double): MeshMessageSample? {
    val cycle = MeshAnimationSpec.MESSAGE_CYCLE_SECONDS
    val progress = (timeSeconds % cycle) / cycle
    val path = MeshAnimationSpec.messagePath
    val segmentCount = path.size - 1
    if (segmentCount <= 0) return null
    val totalProgress = progress * segmentCount
    val segmentIndex = min(totalProgress.toInt(), segmentCount - 1)
    val segmentProgress = totalProgress - segmentIndex
    val from = MeshAnimationSpec.nodes[path[segmentIndex]]
    val to = MeshAnimationSpec.nodes[path[segmentIndex + 1]]
    return MeshMessageSample(
        nx = from.nx + (to.nx - from.nx) * segmentProgress,
        ny = from.ny + (to.ny - from.ny) * segmentProgress,
    )
}
