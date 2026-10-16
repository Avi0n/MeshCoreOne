package one.meshcore.shared.tools.ui

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.tools.DEFAULT_FREQUENCY_MHZ
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.configure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The connected radio's live values the Tools hub hands its tool screens. */
class ToolsHubSeedTest {
    private fun snapshot(frequencyKHz: Long) = RadioDeviceSnapshot(
        frequencyKHz = frequencyKHz,
        bandwidthHz = 250_000,
        spreadingFactor = 11,
        codingRate = 5,
    )

    @Test
    fun losFrequencySeedComesFromTheConnectedRadio() {
        assertEquals(869_525L, losSeedFrequencyKHz(snapshot(869_525)))
        assertNull(losSeedFrequencyKHz(null), "disconnected leaves the tool on its default")
        assertNull(losSeedFrequencyKHz(snapshot(0)), "an unreported frequency is not a seed")
    }

    @Test
    fun seededFrequencyReachesTheFresnelMath() = runTest {
        val viewModel = LineOfSightViewModel(scope = backgroundScope)
        val defaultFrequency = viewModel.frequencyMHz

        viewModel.configure(radioId = { null }, deviceFrequencyKHz = losSeedFrequencyKHz(snapshot(869_525)))

        assertEquals(869.525, viewModel.frequencyMHz)
        assertEquals(DEFAULT_FREQUENCY_MHZ, defaultFrequency, "the unseeded default is the catalog frequency")
    }
}
