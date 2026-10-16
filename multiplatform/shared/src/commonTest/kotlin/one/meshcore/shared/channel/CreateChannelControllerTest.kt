package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CreateChannelControllerTest {
    private val fixedSecret = ByteArray(ChannelSecret.SIZE_BYTES) { 0x5A }

    private fun controller(fixture: ChannelFlowFixture) = CreateChannelController(
        CreateChannelController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            availableSlots = fixture.slotsProvider,
        ),
        generateSecret = { fixedSecret.copyOf() },
    )

    @Test
    fun name_is_truncated_live_to_the_usable_byte_budget() {
        val controller = controller(ChannelFlowFixture())
        controller.setName("A".repeat(MAX_USABLE_NAME_BYTES + 5))
        assertEquals(MAX_USABLE_NAME_BYTES, controller.state.value.name.length)
    }

    @Test
    fun typingAPrivateChannelNameDoesNotRadio() {
        var serviceReads = 0
        val controller = CreateChannelController(
            CreateChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    error("channel service")
                },
                radioId = { error("radio") },
                availableSlots = { error("slots") },
            ),
            generateSecret = { ByteArray(ChannelSecret.SIZE_BYTES) { 0x5A } },
        )
        val secret = controller.state.value.secret.copyOf()

        controller.setName("Ops")

        val state = controller.state.value
        assertEquals(0, serviceReads)
        assertEquals("Ops", state.name)
        assertContentEquals(secret, state.secret)
        assertEquals(false, state.isCreating)
        assertEquals(null, state.createdChannel)
        assertEquals(null, state.error)
        assertEquals(true, state.canCreate)
    }

    @Test
    fun create_writes_the_generated_secret_to_the_first_free_slot_and_flips_to_share() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(2, 3))
        val controller = controller(fixture)
        controller.setName("Ops")
        controller.create()

        val call = fixture.session.setChannelCalls.single()
        assertEquals(2, call.index)
        assertEquals("Ops", call.name)
        assertContentEquals(fixedSecret, call.secret)

        val state = controller.state.value
        assertTrue(state.isCreated)
        assertNotNull(state.createdChannel)
        assertFalse(state.isCreating)
    }

    @Test
    fun create_falls_back_to_slot_one_when_no_free_slots_are_reported() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = emptyList())
        val controller = controller(fixture)
        controller.setName("Ops")
        controller.create()
        assertEquals(ChannelSlots.USER_CHANNEL_MIN_SLOT, fixture.session.setChannelCalls.single().index)
    }

    @Test
    fun share_uri_is_the_channel_add_url_for_the_name_and_secret() {
        val controller = controller(ChannelFlowFixture())
        controller.setName("Ops")
        assertEquals(ChannelUri.export("Ops", fixedSecret), controller.shareUri())
    }

    @Test
    fun create_without_a_device_surfaces_no_device_connected() = runTest {
        val fixture = ChannelFlowFixture(radioId = null)
        val controller = controller(fixture)
        controller.setName("Ops")
        controller.create()
        assertEquals(ChannelActionError.NoDeviceConnected, controller.state.value.error)
        assertFalse(controller.state.value.isCreated)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }

    /**
     * A live radio whose per-connection services are not built yet is its own state on iOS. Reporting it
     * as a disconnected radio sends the user to reconnect a radio that is already connected.
     */
    @Test
    fun create_transport_fault_clears_the_spinner_and_surfaces_the_error() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.session.setChannelError = { ChannelTransportException("socket closed") }
        val controller = controller(fixture)
        controller.setName("Ops")
        controller.create()

        val state = controller.state.value
        assertFalse(state.isCreating)
        assertFalse(state.isCreated)
        val error = state.error as ChannelActionError.Service
        assertTrue(error.error is ChannelServiceError.SessionError)
        assertEquals("socket closed", error.error.cause?.message)
    }

    @Test
    fun create_with_a_live_radio_but_no_services_surfaces_services_unavailable() = runTest {
        val fixture = ChannelFlowFixture().apply { serviceOrNull = null }
        val controller = controller(fixture)
        controller.setName("Ops")
        controller.create()
        assertEquals(ChannelActionError.ServicesUnavailable, controller.state.value.error)
        assertFalse(controller.state.value.isCreated)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }

    @Test
    fun creatingAPrivateChannelWithABlankNameDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val controller = CreateChannelController(
            CreateChannelController.Dependencies(
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
            generateSecret = { ByteArray(ChannelSecret.SIZE_BYTES) },
        )

        controller.create()

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        val state = controller.state.value
        assertEquals("", state.name)
        assertFalse(state.isCreating)
        assertFalse(state.isCreated)
        assertEquals(null, state.error)
    }

    @Test
    fun buildingAPrivateChannelShareLinkDoesNotRadio() = runTest {
        var serviceReads = 0
        val secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0x5A }
        val controller = CreateChannelController(
            CreateChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    error("channel service")
                },
                radioId = { error("radio") },
                availableSlots = { error("slots") },
            ),
            generateSecret = { secret.copyOf() },
        )

        controller.setName("Ops")
        val uri = controller.shareUri()

        assertEquals(0, serviceReads)
        assertEquals(ChannelUri.export("Ops", secret), uri)
        assertFalse(controller.state.value.isCreating)
        assertFalse(controller.state.value.isCreated)
        assertEquals(null, controller.state.value.error)
    }
}
