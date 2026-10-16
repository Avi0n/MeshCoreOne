package one.meshcore.shared.remotenode.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals

class MetricChartLineStripTest {
    @Test
    fun horizontalSegmentIsAQuadOfTheStrokeWidth() {
        val strip = chartLineStrip(
            points = listOf(Offset(0f, 10f), Offset(20f, 10f)),
            strokeWidth = 4f,
        )
        assertEquals(
            listOf(
                Offset(0f, 12f),
                Offset(0f, 8f),
                Offset(20f, 12f),
                Offset(20f, 8f),
            ),
            strip,
        )
    }

    @Test
    fun verticalSegmentOffsetsAlongX() {
        val strip = chartLineStrip(
            points = listOf(Offset(5f, 0f), Offset(5f, 10f)),
            strokeWidth = 2f,
        )
        assertEquals(
            listOf(
                Offset(4f, 0f),
                Offset(6f, 0f),
                Offset(4f, 10f),
                Offset(6f, 10f),
            ),
            strip,
        )
    }

    @Test
    fun fewerThanTwoPointsHaveNoStrip() {
        assertEquals(emptyList(), chartLineStrip(emptyList(), strokeWidth = 2.5f))
        assertEquals(emptyList(), chartLineStrip(listOf(Offset(1f, 1f)), strokeWidth = 2.5f))
    }

    @Test
    fun aRepeatedSampleKeepsThePreviousWidth() {
        val strip = chartLineStrip(
            points = listOf(Offset(0f, 0f), Offset(10f, 0f), Offset(10f, 0f)),
            strokeWidth = 4f,
        )
        assertEquals(Offset(10f, 2f), strip[4])
        assertEquals(Offset(10f, -2f), strip[5])
    }
}
