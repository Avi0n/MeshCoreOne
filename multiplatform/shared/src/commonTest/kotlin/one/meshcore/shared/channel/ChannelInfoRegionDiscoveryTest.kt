package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Region-discovery multi-select on the channel-info controller: discovery surfaces candidate names for
 * the user to pick (it never auto-adds), mirroring the iOS `RegionDiscoveryResultsView` flow.
 */
class ChannelInfoRegionDiscoveryTest {
    private val channel: ChannelDto = channelDto(
        id = ChannelTestIds.fresh(),
        radioID = ChannelTestIds.RADIO,
        index = 2,
        name = "Ops",
    )

    private class Recorder {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
    }

    private fun controller(
        recorder: Recorder,
        discover: suspend () -> RegionDiscoverUiResult,
        knownRegions: List<String> = emptyList(),
    ) = ChannelInfoController(
        channel,
        ChannelInfoController.Dependencies(
            channelService = { null },
            radioId = { ChannelTestIds.RADIO },
            persistNotificationLevel = { _, _ -> },
            persistPinned = { _, _ -> },
            persistFloodScope = { _, _ -> },
            knownRegions = { knownRegions },
            discoverNearbyRegions = discover,
            regionDiscoverMessage = { "no new regions" },
            addKnownRegion = { recorder.added += it },
            removeKnownRegion = { recorder.removed += it },
        ),
    )

    @Test
    fun discovery_with_new_regions_opens_the_multiselect_without_persisting() = runTest {
        val recorder = Recorder()
        val controller = controller(
            recorder,
            discover = { RegionDiscoverUiResult.Completed(newRegions = listOf("Alps", "Rhine"), allTableFull = false) },
        )

        controller.regions.discoverNearbyRegions()

        assertEquals(listOf("Alps", "Rhine"), controller.state.value.discoveredRegions)
        assertNull(controller.state.value.discoveryMessage)
        assertTrue(recorder.added.isEmpty(), "discovery must not auto-add regions")
    }

    @Test
    fun discovery_with_no_new_regions_shows_a_status_message() = runTest {
        val recorder = Recorder()
        val controller = controller(
            recorder,
            discover = { RegionDiscoverUiResult.Completed(newRegions = emptyList(), allTableFull = false) },
        )

        controller.regions.discoverNearbyRegions()

        assertNull(controller.state.value.discoveredRegions)
        assertEquals("no new regions", controller.state.value.discoveryMessage)
    }

    @Test
    fun confirming_selected_regions_persists_them_and_closes_the_multiselect() = runTest {
        val recorder = Recorder()
        val controller = controller(
            recorder,
            discover = { RegionDiscoverUiResult.Completed(newRegions = listOf("Alps", "Rhine"), allTableFull = false) },
        )
        controller.regions.discoverNearbyRegions()

        controller.regions.confirmDiscoveredRegions(listOf("Alps"))

        assertEquals(listOf("Alps"), recorder.added)
        assertEquals(listOf("Alps"), controller.knownRegions())
        assertNull(controller.state.value.discoveredRegions)
    }

    @Test
    fun managed_regions_refresh_channel_info_without_reopening_it() = runTest {
        val recorder = Recorder()
        val controller = controller(
            recorder,
            discover = { RegionDiscoverUiResult.Completed(emptyList(), allTableFull = false) },
            knownRegions = listOf("Rhine"),
        )

        controller.regions.addRegion("Alps")
        assertEquals(listOf("Alps", "Rhine"), controller.knownRegions())

        controller.regions.removeRegion("Rhine")
        assertEquals(listOf("Alps"), controller.knownRegions())
        assertEquals(listOf("Alps"), recorder.added)
        assertEquals(listOf("Rhine"), recorder.removed)
    }

    @Test
    fun dismissing_the_multiselect_adds_nothing() = runTest {
        val recorder = Recorder()
        val controller = controller(
            recorder,
            discover = { RegionDiscoverUiResult.Completed(newRegions = listOf("Alps"), allTableFull = false) },
        )
        controller.regions.discoverNearbyRegions()

        controller.regions.dismissDiscoveryResults()

        assertNull(controller.state.value.discoveredRegions)
        assertTrue(recorder.added.isEmpty())
    }

    @Test
    fun retained_controller_uses_replacement_dependencies() = runTest {
        val initialRecorder = Recorder()
        val replacementRecorder = Recorder()
        var dependencies = deps(
            recorder = initialRecorder,
            discover = { RegionDiscoverUiResult.Completed(emptyList(), allTableFull = false) },
            message = "initial message",
        )
        val controller = ChannelInfoController(channel) { dependencies }

        controller.regions.discoverNearbyRegions()
        assertEquals("initial message", controller.state.value.discoveryMessage)

        dependencies = deps(
            recorder = replacementRecorder,
            discover = { RegionDiscoverUiResult.SendFailed },
            message = "replacement message",
        )
        controller.regions.discoverNearbyRegions()
        controller.regions.addRegion("Alps")

        assertEquals("replacement message", controller.state.value.discoveryMessage)
        assertTrue(initialRecorder.added.isEmpty())
        assertEquals(listOf("Alps"), replacementRecorder.added)
    }

    private fun deps(recorder: Recorder, discover: suspend () -> RegionDiscoverUiResult, message: String) =
        ChannelInfoController.Dependencies(
            channelService = { null },
            radioId = { ChannelTestIds.RADIO },
            persistNotificationLevel = { _, _ -> },
            persistPinned = { _, _ -> },
            persistFloodScope = { _, _ -> },
            discoverNearbyRegions = discover,
            regionDiscoverMessage = { message },
            addKnownRegion = { recorder.added += it },
            removeKnownRegion = { recorder.removed += it },
        )
}
