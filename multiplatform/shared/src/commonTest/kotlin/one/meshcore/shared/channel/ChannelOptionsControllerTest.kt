package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelOptionsControllerTest {
    private fun controller(fixture: ChannelFlowFixture) = ChannelOptionsController(
        ChannelOptionsController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            maxChannels = fixture.maxChannelsProvider,
        ),
    )

    @Test
    fun load_computes_free_slots_and_public_presence_from_the_channel_list() = runTest {
        val fixture = ChannelFlowFixture(maxChannels = 4)
        fixture.seedChannel(index = 0, name = "Public")
        fixture.seedChannel(index = 2, name = "Ops")
        val controller = controller(fixture)
        controller.load()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertEquals(listOf(1, 3), state.availableSlots)
        assertTrue(state.hasPublicChannel)
        assertFalse(state.noAvailableSlots)
    }

    @Test
    fun load_without_a_device_marks_the_hub_unavailable() = runTest {
        val fixture = ChannelFlowFixture().apply { serviceOrNull = null }
        val controller = controller(fixture)
        controller.load()
        val state = controller.state.value
        assertFalse(state.isLoading)
        assertTrue(state.loadUnavailable)
        assertFalse(state.noAvailableSlots)
        assertFalse(state.hasPublicChannel)
    }

    @Test
    fun reload_after_disconnect_revokes_slots_from_the_retired_service() = runTest {
        val fixture = ChannelFlowFixture(maxChannels = 4)
        val controller = controller(fixture)
        controller.load()
        assertEquals(listOf(1, 2, 3), controller.state.value.availableSlots)

        fixture.serviceOrNull = null
        controller.load()

        val state = controller.state.value
        assertTrue(state.loadUnavailable)
        assertEquals(emptyList(), state.availableSlots)
        assertFalse(state.hasPublicChannel)
    }

    @Test
    fun load_when_the_channel_read_fails_marks_the_hub_unavailable() = runTest {
        val controller = controllerOver(throwingStore { error("db locked") })
        controller.load()

        val state = controller.state.value
        assertFalse(state.isLoading)
        assertTrue(state.loadUnavailable)
        assertEquals(emptyList(), state.availableSlots)
        assertFalse(state.hasPublicChannel)
    }

    @Test
    fun load_when_the_channel_read_is_cancelled_propagates() = runTest {
        val cancelled = CancellationException("superseded")
        val controller = controllerOver(throwingStore { throw cancelled })

        val thrown = assertFailsWith<CancellationException> { controller.load() }
        assertSame(cancelled, thrown)
        assertTrue(controller.state.value.isLoading)
        assertFalse(controller.state.value.loadUnavailable)
    }

    @Test
    fun load_when_the_channel_read_throws_an_error_propagates() = runTest {
        val fault = ChannelOptionsLoadProbeError()
        val controller = controllerOver(throwingStore { throw fault })

        val thrown = assertFailsWith<ChannelOptionsLoadProbeError> { controller.load() }
        assertSame(fault, thrown)
        assertTrue(controller.state.value.isLoading)
        assertFalse(controller.state.value.loadUnavailable)
    }

    private fun controllerOver(store: ChannelStore) = ChannelOptionsController(
        ChannelOptionsController.Dependencies(
            channelService = { ChannelService(session = FakeChannelSession(), store = store) },
            radioId = { ChannelTestIds.RADIO },
            maxChannels = { 8 },
        ),
    )

    private fun throwingStore(fetch: suspend (Uuid) -> List<ChannelDto>): ChannelStore =
        object : ChannelStore by InMemoryChannelStore() {
            override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = fetch(radioID)
        }
}

private class ChannelOptionsLoadProbeError : Error("probe")
