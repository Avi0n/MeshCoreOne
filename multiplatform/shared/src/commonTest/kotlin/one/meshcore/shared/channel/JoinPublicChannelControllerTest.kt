package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JoinPublicChannelControllerTest {
    private fun controller(fixture: ChannelFlowFixture) = JoinPublicChannelController(
        JoinPublicChannelController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
        ),
    )

    @Test
    fun join_writes_the_fixed_public_secret_to_slot_zero() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = controller(fixture)

        var joined: Boolean? = null
        controller.join { joined = it != null }

        assertTrue(joined == true)
        val call = fixture.session.setChannelCalls.single()
        assertEquals(ChannelSecret.PUBLIC_CHANNEL_SLOT, call.index)
        assertEquals(ChannelSecret.PUBLIC_CHANNEL_NAME, call.name)
        assertContentEquals(ChannelSecret.publicChannelSecret(), call.secret)
    }

    @Test
    fun join_returns_the_slot_zero_channel() = runTest {
        val fixture = ChannelFlowFixture()
        val controller = controller(fixture)
        controller.join { }
        val channel = fixture.store.fetchChannel(ChannelTestIds.RADIO, 0)
        assertNotNull(channel)
        assertEquals(0, channel.index)
    }

    @Test
    fun join_transport_fault_clears_the_spinner_and_surfaces_the_error() = runTest {
        val fixture = ChannelFlowFixture()
        fixture.session.setChannelError = { ChannelTransportException("socket closed") }
        val controller = controller(fixture)
        var joined: Boolean? = null
        controller.join { joined = it != null }

        val state = controller.state.value
        assertEquals(false, state.isJoining)
        assertEquals(null, joined)
        val error = state.error as ChannelActionError.Service
        assertTrue(error.error is ChannelServiceError.SessionError)
        assertEquals("socket closed", error.error.cause?.message)
    }

    @Test
    fun joiningThePublicChannelWhileDisconnectedDoesNotRadio() = runTest {
        val fixture = ChannelFlowFixture(radioId = null)
        var serviceReads = 0
        var radioReads = 0
        val controller = JoinPublicChannelController(
            JoinPublicChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    fixture.serviceProvider()
                },
                radioId = {
                    radioReads += 1
                    fixture.radioProvider()
                },
            ),
        )

        var joined = false
        controller.join { joined = true }

        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)
        assertEquals(false, joined)
        assertEquals(ChannelActionError.NoDeviceConnected, controller.state.value.error)
        assertEquals(false, controller.state.value.isJoining)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }
}
