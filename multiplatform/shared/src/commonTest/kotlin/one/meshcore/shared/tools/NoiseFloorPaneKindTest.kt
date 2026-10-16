package one.meshcore.shared.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class NoiseFloorPaneKindTest {
    @Test
    fun aFailedFirstPollIsAnErrorNotCollecting() {
        assertEquals(
            NoiseFloorPaneKind.ERROR,
            noiseFloorPaneKind(hasFetcher = true, hasReading = false, errorMessage = "unable to read"),
        )
    }

    @Test
    fun aLiveFetcherWithNoSampleYetIsCollecting() {
        assertEquals(
            NoiseFloorPaneKind.COLLECTING,
            noiseFloorPaneKind(hasFetcher = true, hasReading = false, errorMessage = null),
        )
    }

    @Test
    fun noFetcherIsDisconnectedEvenIfAnErrorStringIsSet() {
        assertEquals(
            NoiseFloorPaneKind.DISCONNECTED,
            noiseFloorPaneKind(hasFetcher = false, hasReading = false, errorMessage = "unable to read"),
        )
    }

    @Test
    fun noiseFloorPollsOnlyWhileTheRadioIsReady() {
        val fetch: suspend () -> NoiseFloorReading? = { null }
        assertEquals(null, noiseFloorFetcher(isRadioReady = false, fetchReading = fetch))
        assertEquals(fetch, noiseFloorFetcher(isRadioReady = true, fetchReading = fetch))
        assertEquals(null, noiseFloorFetcher(isRadioReady = true, fetchReading = null))
    }

    @Test
    fun aReadingOpensTheContentPane() {
        assertEquals(
            NoiseFloorPaneKind.CONTENT,
            noiseFloorPaneKind(hasFetcher = true, hasReading = true, errorMessage = "unable to read"),
        )
    }
}
