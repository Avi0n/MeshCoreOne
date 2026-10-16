package one.meshcore.shared.tools.ui

import one.meshcore.shared.rf.ProfileSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FresnelChartGeometryTest {
    @Test
    fun theFirstChartPointMovesAndTheRestLine() {
        assertEquals(emptyList(), chartStrokeVertices(emptyList()))
        val strokes = chartStrokeVertices(listOf(0f to 1f, 2f to 3f, 4f to 5f))
        assertEquals(ChartStroke.MOVE, strokes.first().stroke)
        assertEquals(0f, strokes.first().x)
        assertEquals(1f, strokes.first().y)
        assertEquals(listOf(ChartStroke.LINE, ChartStroke.LINE), strokes.drop(1).map { it.stroke })
    }

    @Test
    fun obstructedSamplesKeepOnlyGroundInsideTheInnerZone() {
        val clear = ProfileSample(x = 0.0, yTerrain = 100.0, yLos = 200.0, fresnelRadius = 50.0)
        val blocked = ProfileSample(x = 500.0, yTerrain = 185.0, yLos = 210.0, fresnelRadius = 60.0)
        assertFalse(clear.isObstructed)
        assertTrue(blocked.isObstructed)
        assertEquals(listOf(blocked), obstructedProfileSamples(listOf(clear, blocked)))
    }

    @Test
    fun repeaterSliderExistsOnlyWhenDragAndPositionExist() {
        val drag = { _: Double -> }
        val slider = fresnelRepeaterSlider(drag, fraction = 0.4)
        assertEquals(0.4, slider?.fraction)
        assertEquals(drag, slider?.onDrag)
        assertEquals(null, fresnelRepeaterSlider(onDrag = null, fraction = 0.4))
        assertEquals(null, fresnelRepeaterSlider(drag, fraction = null))
    }
}
