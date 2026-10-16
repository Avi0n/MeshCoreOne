package one.meshcore.shared.rf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FresnelZoneRendererTest {
    @Test
    fun losHeightInterpolatesEndpoints() {
        assertEquals(10.0, FresnelZoneRenderer.losHeight(0.0, 100.0, 10.0, 30.0))
        assertEquals(30.0, FresnelZoneRenderer.losHeight(100.0, 100.0, 10.0, 30.0))
        assertEquals(20.0, FresnelZoneRenderer.losHeight(50.0, 100.0, 10.0, 30.0))
    }

    @Test
    fun buildProfileSamplesPreserveXAndProduceFresnel() {
        val profile = listOf(
            ElevationSample(GeoCoordinate(37.0, -122.0), elevation = 100.0, distanceFromAMeters = 0.0),
            ElevationSample(GeoCoordinate(37.01, -122.01), elevation = 120.0, distanceFromAMeters = 500.0),
            ElevationSample(GeoCoordinate(37.02, -122.02), elevation = 110.0, distanceFromAMeters = 1000.0),
        )
        val samples = FresnelZoneRenderer.buildProfileSamples(
            elevationProfile = profile,
            pointAHeight = 10.0,
            pointBHeight = 10.0,
            frequencyMHz = 910.0,
            refractionK = 1.33,
        )
        assertEquals(3, samples.size)
        assertEquals(0.0, samples[0].x)
        assertEquals(1000.0, samples[2].x)
        // Mid-path Fresnel radius should be positive at 910 MHz over 1 km.
        assertTrue(samples[1].fresnelRadius > 0.0)
        assertEquals(100.0 + 10.0, samples[0].yLos, 0.001)
        assertEquals(110.0 + 10.0, samples[2].yLos, 0.001)
    }
}
