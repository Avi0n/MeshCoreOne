package one.meshcore.shared.rf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The terrain chart fills two Fresnel bands. The inner one is the 60% clearance threshold the whole
 * verdict is defined against ([ProfileSample.isObstructed]), so a chart that fills only the outer
 * envelope shows a responder a verdict whose basis is not on screen.
 */
class FresnelBandOutlineTest {
    private val samples = listOf(
        ProfileSample(x = 0.0, yTerrain = 100.0, yLos = 200.0, fresnelRadius = 50.0),
        ProfileSample(x = 500.0, yTerrain = 185.0, yLos = 210.0, fresnelRadius = 60.0),
        ProfileSample(x = 1000.0, yTerrain = 120.0, yLos = 220.0, fresnelRadius = 40.0),
    )

    @Test
    fun bothBandsAreDrawnWithTheInnerOneOnTop() {
        val outlines = fresnelBandOutlines(samples)

        assertEquals(
            listOf(FresnelBand.OUTER, FresnelBand.INNER),
            outlines.map { it.band },
            "the inner 60% band fills over the outer envelope, as iOS layers them",
        )
        assertEquals(0.6, FresnelBand.INNER.fraction)
        assertEquals(1.0, FresnelBand.OUTER.fraction)
    }

    @Test
    fun theInnerBandRunsAlongThe60PercentEnvelopeAndClampsToTerrain() {
        val inner = fresnelBandOutlines(samples).single { it.band == FresnelBand.INNER }

        // Top edge left to right, then the bottom edge back again: one closed polygon per band.
        assertEquals(samples.size * 2, inner.vertices.size)
        val top = inner.vertices.take(samples.size)
        val bottom = inner.vertices.drop(samples.size)
        samples.forEachIndexed { index, sample ->
            assertEquals(sample.x, top[index].x)
            assertEquals(sample.yLos + sample.fresnelRadius * FresnelBand.INNER.fraction, top[index].elevation)
        }
        // The middle sample's terrain rises into the inner zone, so the fill stops at the ground there.
        assertEquals(185.0, bottom.first { it.x == 500.0 }.elevation, "the fill is clamped to terrain")
        assertTrue(samples[1].isObstructed, "that sample is exactly what the verdict calls obstructed")
    }

    @Test
    fun theOuterBandKeepsTheFullFirstFresnelEnvelope() {
        val outer = fresnelBandOutlines(samples).single { it.band == FresnelBand.OUTER }

        val top = outer.vertices.take(samples.size)
        samples.forEachIndexed { index, sample ->
            assertEquals(sample.yTop, top[index].elevation)
        }
    }

    @Test
    fun aProfileTooShortToFillProducesNothing() {
        assertTrue(fresnelBandOutlines(samples.take(1)).isEmpty())
    }
}
