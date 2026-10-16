package one.meshcore.shared.tools.ui

import androidx.compose.ui.graphics.Path
import one.meshcore.shared.rf.ProfileSample

internal enum class ChartStroke {
    MOVE,
    LINE,
}

internal data class ChartVertex(val x: Float, val y: Float, val stroke: ChartStroke)

/** The first plotted point starts a subpath. Every later point continues it. */
internal fun chartStrokeVertices(points: List<Pair<Float, Float>>): List<ChartVertex> =
    points.mapIndexed { index, (x, y) ->
        ChartVertex(
            x = x,
            y = y,
            stroke = if (index == 0) ChartStroke.MOVE else ChartStroke.LINE,
        )
    }

internal fun Path.traceChart(points: List<Pair<Float, Float>>) {
    chartStrokeVertices(points).forEach { vertex ->
        when (vertex.stroke) {
            ChartStroke.MOVE -> moveTo(vertex.x, vertex.y)
            ChartStroke.LINE -> lineTo(vertex.x, vertex.y)
        }
    }
}

internal fun obstructedProfileSamples(samples: List<ProfileSample>): List<ProfileSample> =
    samples.filter { it.isObstructed }

internal data class FresnelRepeaterSlider(val fraction: Double, val onDrag: (Double) -> Unit)

internal fun fresnelRepeaterSlider(onDrag: ((Double) -> Unit)?, fraction: Double?): FresnelRepeaterSlider? =
    if (onDrag != null && fraction != null) FresnelRepeaterSlider(fraction, onDrag) else null
