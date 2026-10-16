package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JoinHashtagChannelControllerTest {
    private fun controller(fixture: ChannelFlowFixture) = JoinHashtagChannelController(
        JoinHashtagChannelController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            availableSlots = fixture.slotsProvider,
        ),
    )

    @Test
    fun name_is_sanitized_live_lowercasing_and_stripping_disallowed_characters() {
        val controller = controller(ChannelFlowFixture())
        controller.setName("Bad Name!")
        assertEquals("badname", controller.state.value.name)
        controller.setName("--foo")
        assertEquals("foo", controller.state.value.name)
    }

    @Test
    fun typingAHashtagChannelNameDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val controller = JoinHashtagChannelController(
            JoinHashtagChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    error("channel service")
                },
                radioId = {
                    radioReads += 1
                    error("radio")
                },
                availableSlots = { error("slots") },
            ),
        )

        controller.setName("Bad Name!")

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals("badname", controller.state.value.name)
        assertEquals(null, controller.state.value.existingChannel)
        assertEquals(null, controller.state.value.error)
        assertEquals(false, controller.state.value.isJoining)
    }

    @Test
    fun joiningABlankHashtagChannelDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        var joined = 0
        val controller = JoinHashtagChannelController(
            JoinHashtagChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    error("channel service")
                },
                radioId = {
                    radioReads += 1
                    error("radio")
                },
                availableSlots = { error("slots") },
            ),
        )

        controller.join { joined += 1 }

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, joined)
        assertEquals("", controller.state.value.name)
        assertEquals(false, controller.state.value.isJoining)
        assertEquals(null, controller.state.value.error)
        assertEquals(null, controller.state.value.existingChannel)
    }

    @Test
    fun loadingHashtagChannelsWhileDisconnectedDoesNotRadio() = runTest {
        var serviceReads = 0
        val controller = JoinHashtagChannelController(
            JoinHashtagChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    null
                },
                radioId = { error("radio") },
                availableSlots = { error("slots") },
            ),
        )

        controller.loadExisting()

        assertEquals(1, serviceReads)
        val state = controller.state.value
        assertEquals("", state.name)
        assertEquals(null, state.existingChannel)
        assertEquals(null, state.error)
        assertEquals(false, state.isJoining)
    }

    @Test
    fun join_writes_the_hashtag_name_and_passphrase_deriving_the_shared_secret() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(2))
        val controller = controller(fixture)
        controller.setName("general")
        controller.join { }

        val call = fixture.session.setChannelCalls.single()
        assertEquals(2, call.index)
        assertEquals("#general", call.name)
        // §2.3: the secret is sha256("#general")[0:16] so every client that types the hashtag matches.
        assertContentEquals(ChannelSecret.hashSecret("#general"), call.secret)
    }

    @Test
    fun load_existing_fails_open_when_the_channel_read_fails() = runTest {
        val controller = controllerOver(throwingChannelStore { error("db locked") })
        controller.setName("general")

        controller.loadExisting()

        assertEquals("general", controller.state.value.name)
        assertNull(controller.state.value.existingChannel)
        assertNull(controller.state.value.error)
    }

    @Test
    fun load_existing_when_the_channel_read_is_cancelled_propagates() = runTest {
        val cancelled = CancellationException("superseded")
        val controller = controllerOver(throwingChannelStore { throw cancelled })
        controller.setName("general")

        val thrown = assertFailsWith<CancellationException> { controller.loadExisting() }
        assertSame(cancelled, thrown)
        assertEquals("general", controller.state.value.name)
        assertNull(controller.state.value.existingChannel)
    }

    @Test
    fun load_existing_when_the_channel_read_throws_an_error_propagates() = runTest {
        val fault = JoinHashtagLoadProbeError()
        val controller = controllerOver(throwingChannelStore { throw fault })
        controller.setName("general")

        val thrown = assertFailsWith<JoinHashtagLoadProbeError> { controller.loadExisting() }
        assertSame(fault, thrown)
        assertEquals("general", controller.state.value.name)
        assertNull(controller.state.value.existingChannel)
    }

    @Test
    fun an_already_joined_channel_is_matched_case_insensitively_and_opened_without_a_write() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.seedChannel(index = 2, name = "#general")
        val controller = controller(fixture)
        controller.setName("GENERAL")
        controller.loadExisting()

        assertNotNull(controller.state.value.existingChannel)
        var opened: Boolean? = null
        controller.join { opened = it != null }
        assertTrue(opened == true)
        assertNull(fixture.session.setChannelCalls.firstOrNull())
    }

    @Test
    fun reload_after_disconnect_preserves_the_name_and_clears_the_retired_match() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.seedChannel(index = 2, name = "#general")
        val controller = controller(fixture)
        controller.setName("general")
        controller.loadExisting()
        assertNotNull(controller.state.value.existingChannel)

        fixture.serviceOrNull = null
        controller.loadExisting()

        assertEquals("general", controller.state.value.name)
        assertNull(controller.state.value.existingChannel)
    }

    private fun controllerOver(store: ChannelStore) = JoinHashtagChannelController(
        JoinHashtagChannelController.Dependencies(
            channelService = { ChannelService(session = FakeChannelSession(), store = store) },
            radioId = { ChannelTestIds.RADIO },
            availableSlots = { listOf(1) },
        ),
    )

    private fun throwingChannelStore(fetch: suspend (Uuid) -> List<ChannelDto>): ChannelStore =
        object : ChannelStore by InMemoryChannelStore() {
            override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = fetch(radioID)
        }
}

private class JoinHashtagLoadProbeError : Error("probe")
