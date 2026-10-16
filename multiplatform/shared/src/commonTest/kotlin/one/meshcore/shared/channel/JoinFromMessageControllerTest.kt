package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.MeshCoreDeepLink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JoinFromMessageControllerTest {
    private val secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0x3E }

    // MARK: Channel-link confirmation

    private fun linkController(fixture: ChannelFlowFixture, regionScope: String? = null) = JoinChannelLinkController(
        MeshCoreDeepLink.Channel(name = "Ops", secret = secret, regionScope = regionScope),
        JoinChannelLinkController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            maxChannels = fixture.maxChannelsProvider,
        ),
    )

    @Test
    fun link_load_resolves_ready_when_a_free_slot_exists() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = linkController(fixture)
        controller.load()
        assertEquals(ChannelJoinPhase.READY, controller.state.value.phase)
    }

    @Test
    fun link_load_resolves_missing_device_without_a_radio() = runTest {
        val fixture = ChannelFlowFixture(radioId = null)
        val controller = linkController(fixture)
        controller.load()
        assertEquals(ChannelJoinPhase.MISSING_DEVICE, controller.state.value.phase)
    }

    @Test
    fun link_load_resolves_missing_device_when_disconnected_with_a_last_radio() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.serviceOrNull = null
        val controller = linkController(fixture)
        controller.load()
        assertEquals(ChannelJoinPhase.MISSING_DEVICE, controller.state.value.phase)
    }

    @Test
    fun link_reload_after_disconnect_revokes_slots_from_the_retired_service() = runTest {
        val first = ChannelFlowFixture()
        val replacement = ChannelFlowFixture()
        var liveService: ChannelService? = first.service
        val controller = JoinChannelLinkController(
            MeshCoreDeepLink.Channel(name = "Ops", secret = secret),
            JoinChannelLinkController.Dependencies(
                channelService = { liveService },
                radioId = { ChannelTestIds.RADIO },
                maxChannels = { 8 },
            ),
        )
        controller.load()
        assertEquals(ChannelJoinPhase.READY, controller.state.value.phase)

        liveService = null
        controller.load()
        liveService = replacement.service
        controller.join { error("stale slot joined") }

        assertEquals(ChannelActionError.NoSlots, controller.state.value.error)
        assertTrue(replacement.session.setChannelCalls.isEmpty())
    }

    @Test
    fun loadingAChannelLinkWhileDisconnectedDoesNotRadio() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.serviceOrNull = null
        val controller = linkController(fixture)

        controller.load()

        assertEquals(ChannelJoinPhase.MISSING_DEVICE, controller.state.value.phase)
        assertEquals(false, controller.state.value.isJoining)
        assertEquals(0, controller.state.value.successTick)
        assertEquals(null, controller.state.value.error)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }

    @Test
    fun joiningAChannelLinkWhileDisconnectedDoesNotRadio() = runTest {
        val fixture = ChannelFlowFixture()
        var serviceReads = 0
        var radioReads = 0
        var radioMissing = false
        val controller = JoinChannelLinkController(
            MeshCoreDeepLink.Channel(name = "Ops", secret = secret),
            JoinChannelLinkController.Dependencies(
                channelService = {
                    serviceReads += 1
                    fixture.serviceProvider()
                },
                radioId = {
                    radioReads += 1
                    if (radioMissing) null else fixture.radioProvider()
                },
                maxChannels = fixture.maxChannelsProvider,
            ),
        )
        controller.load()
        assertEquals(ChannelJoinPhase.READY, controller.state.value.phase)
        serviceReads = 0
        radioReads = 0
        radioMissing = true

        var joined = false
        controller.join { joined = true }

        val state = controller.state.value
        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)
        assertEquals(false, joined)
        assertEquals(ChannelJoinPhase.READY, state.phase)
        assertEquals(ChannelActionError.NoDeviceConnected, state.error)
        assertEquals(false, state.isJoining)
        assertEquals(0, state.successTick)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }

    @Test
    fun link_load_resolves_no_slots_when_the_channel_read_fails() = runTest {
        val controller = linkControllerOver(throwingChannelStore { error("db locked") })

        controller.load()

        assertEquals(ChannelJoinPhase.NO_SLOTS, controller.state.value.phase)
    }

    @Test
    fun link_load_when_the_channel_read_is_cancelled_propagates() = runTest {
        val cancelled = CancellationException("superseded")
        val controller = linkControllerOver(throwingChannelStore { throw cancelled })

        val thrown = assertFailsWith<CancellationException> { controller.load() }
        assertSame(cancelled, thrown)
        assertEquals(ChannelJoinPhase.LOADING, controller.state.value.phase)
    }

    @Test
    fun link_load_when_the_channel_read_throws_an_error_propagates() = runTest {
        val fault = JoinSlotLoadProbeError()
        val controller = linkControllerOver(throwingChannelStore { throw fault })

        val thrown = assertFailsWith<JoinSlotLoadProbeError> { controller.load() }
        assertSame(fault, thrown)
        assertEquals(ChannelJoinPhase.LOADING, controller.state.value.phase)
    }

    @Test
    fun load_available_slots_returns_empty_when_the_read_fails() = runTest {
        val service =
            ChannelService(session = FakeChannelSession(), store = throwingChannelStore { error("db locked") })
        assertEquals(emptyList(), loadAvailableSlots(service, ChannelTestIds.RADIO, 8))
    }

    @Test
    fun load_available_slots_rethrows_cancellation() = runTest {
        val cancelled = CancellationException("superseded")
        val service = ChannelService(
            session = FakeChannelSession(),
            store = throwingChannelStore { throw cancelled },
        )
        val thrown = assertFailsWith<CancellationException> {
            loadAvailableSlots(service, ChannelTestIds.RADIO, 8)
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun load_available_slots_rethrows_an_error() = runTest {
        val fault = JoinSlotLoadProbeError()
        val service = ChannelService(
            session = FakeChannelSession(),
            store = throwingChannelStore { throw fault },
        )
        val thrown = assertFailsWith<JoinSlotLoadProbeError> {
            loadAvailableSlots(service, ChannelTestIds.RADIO, 8)
        }
        assertSame(fault, thrown)
    }

    @Test
    fun link_load_resolves_no_slots_when_every_user_slot_is_taken() = runTest {
        val fixture = ChannelFlowFixture(maxChannels = 2)
        fixture.seedChannel(index = 1, name = "Taken")
        val controller = linkController(fixture)
        controller.load()
        assertEquals(ChannelJoinPhase.NO_SLOTS, controller.state.value.phase)
    }

    @Test
    fun link_join_writes_the_link_secret_and_reports_success() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = linkController(fixture)
        controller.load()

        var joined = false
        controller.join { joined = true }

        assertTrue(joined)
        val call = fixture.session.setChannelCalls.single()
        assertEquals("Ops", call.name)
        assertContentEquals(secret, call.secret)
        assertEquals(1, controller.state.value.successTick)
    }

    @Test
    fun link_join_with_region_scope_persists_the_region_flood_scope() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = linkController(fixture, regionScope = "testregion")

        controller.load()
        var joinedScope: ChannelFloodScope? = null
        controller.join { joinedScope = it.floodScope }

        assertEquals(ChannelFloodScope.Region("testregion"), joinedScope)
        val row = fixture.store.fetchChannels(ChannelTestIds.RADIO).single { it.name == "Ops" }
        assertEquals(ChannelFloodScope.Region("testregion"), row.floodScope)
    }

    @Test
    fun link_join_without_region_scope_leaves_the_default_inherit_scope() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = linkController(fixture)

        controller.load()
        var joinedScope: ChannelFloodScope? = null
        controller.join { joinedScope = it.floodScope }

        assertEquals(ChannelFloodScope.Inherit, joinedScope)
        val row = fixture.store.fetchChannels(ChannelTestIds.RADIO).single { it.name == "Ops" }
        assertEquals(ChannelFloodScope.Inherit, row.floodScope)
    }

    private fun linkControllerOver(store: ChannelStore) = JoinChannelLinkController(
        MeshCoreDeepLink.Channel(name = "Ops", secret = secret),
        JoinChannelLinkController.Dependencies(
            channelService = { ChannelService(session = FakeChannelSession(), store = store) },
            radioId = { ChannelTestIds.RADIO },
            maxChannels = { 8 },
        ),
    )

    private fun throwingChannelStore(fetch: suspend (Uuid) -> List<ChannelDto>): ChannelStore =
        object : ChannelStore by InMemoryChannelStore() {
            override suspend fun fetchChannels(radioID: Uuid): List<ChannelDto> = fetch(radioID)
        }

    // MARK: Hashtag-from-message (the W3-S2 seam consumer)

    private fun hashtagController(rawName: String, fixture: ChannelFlowFixture) = JoinHashtagFromMessageController(
        rawName,
        JoinHashtagFromMessageController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            maxChannels = fixture.maxChannelsProvider,
        ),
    )

    @Test
    fun hashtag_from_message_normalizes_the_tapped_name_and_writes_the_shared_secret() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = hashtagController("#General", fixture)
        assertEquals("#general", controller.fullChannelName)
        controller.load()

        var joined = false
        controller.join { joined = true }

        assertTrue(joined)
        val call = fixture.session.setChannelCalls.single()
        assertEquals("#general", call.name)
        assertContentEquals(ChannelSecret.hashSecret("#general"), call.secret)
    }

    @Test
    fun hashtag_from_message_load_resolves_missing_device_when_disconnected_with_a_last_radio() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.serviceOrNull = null
        val controller = hashtagController("#ops", fixture)
        controller.load()
        assertEquals(ChannelJoinPhase.MISSING_DEVICE, controller.state.value.phase)
    }

    @Test
    fun hashtag_reload_after_disconnect_revokes_slots_from_the_retired_service() = runTest {
        val first = ChannelFlowFixture()
        val replacement = ChannelFlowFixture()
        var liveService: ChannelService? = first.service
        val controller = JoinHashtagFromMessageController(
            "#ops",
            JoinHashtagFromMessageController.Dependencies(
                channelService = { liveService },
                radioId = { ChannelTestIds.RADIO },
                maxChannels = { 8 },
            ),
        )
        controller.load()
        assertEquals(ChannelJoinPhase.READY, controller.state.value.phase)

        liveService = null
        controller.load()
        liveService = replacement.service
        controller.join { error("stale slot joined") }

        assertEquals(ChannelActionError.NoSlots, controller.state.value.error)
        assertTrue(replacement.session.setChannelCalls.isEmpty())
    }

    @Test
    fun joiningAHashtagFromAMessageWhileDisconnectedDoesNotRadio() = runTest {
        val fixture = ChannelFlowFixture()
        var serviceReads = 0
        var radioReads = 0
        var radioMissing = false
        val controller = JoinHashtagFromMessageController(
            "#ops",
            JoinHashtagFromMessageController.Dependencies(
                channelService = {
                    serviceReads += 1
                    fixture.serviceProvider()
                },
                radioId = {
                    radioReads += 1
                    if (radioMissing) null else fixture.radioProvider()
                },
                maxChannels = fixture.maxChannelsProvider,
            ),
        )
        controller.load()
        assertEquals(ChannelJoinPhase.READY, controller.state.value.phase)
        serviceReads = 0
        radioReads = 0
        radioMissing = true

        var joined = false
        controller.join { joined = true }

        val state = controller.state.value
        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)
        assertEquals(false, joined)
        assertEquals(ChannelJoinPhase.READY, state.phase)
        assertEquals(ChannelActionError.NoDeviceConnected, state.error)
        assertEquals(false, state.isJoining)
        assertEquals(0, state.successTick)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }

    @Test
    fun hashtag_from_message_rejects_an_invalid_name() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = hashtagController("###", fixture)
        controller.load()
        controller.join { }
        assertEquals(ChannelActionError.InvalidName, controller.state.value.error)
    }
}

private class JoinSlotLoadProbeError : Error("probe")
