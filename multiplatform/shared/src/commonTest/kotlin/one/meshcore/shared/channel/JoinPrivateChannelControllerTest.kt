package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JoinPrivateChannelControllerTest {
    private fun controller(fixture: ChannelFlowFixture) = JoinPrivateChannelController(
        JoinPrivateChannelController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            availableSlots = fixture.slotsProvider,
        ),
    )

    @Test
    fun secret_field_is_filtered_to_uppercase_hex_and_validity_tracks_length() {
        val controller = controller(ChannelFlowFixture())
        controller.setSecretHex("ab cd ef zz")
        assertEquals("ABCDEF", controller.state.value.secretHex)
        assertFalse(controller.state.value.isValidSecret)
        assertTrue(controller.state.value.footerIsError)

        controller.setSecretHex("a".repeat(ChannelSecret.SIZE_BYTES * 2))
        assertTrue(controller.state.value.isValidSecret)
        assertFalse(controller.state.value.footerIsError)
    }

    @Test
    fun typingAPrivateChannelSecretDoesNotRadio() {
        var serviceReads = 0
        var radioReads = 0
        var slotReads = 0
        val fixture = ChannelFlowFixture()
        val controller = JoinPrivateChannelController(
            JoinPrivateChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    fixture.serviceProvider()
                },
                radioId = {
                    radioReads += 1
                    fixture.radioProvider()
                },
                availableSlots = {
                    slotReads += 1
                    fixture.slotsProvider()
                },
            ),
        )
        assertEquals("", controller.state.value.secretHex)
        assertEquals("", controller.state.value.name)
        assertFalse(controller.state.value.canJoin)

        controller.setSecretHex("ab cd ef zz")

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, slotReads)
        assertEquals("ABCDEF", controller.state.value.secretHex)
        assertEquals("", controller.state.value.name)
        assertFalse(controller.state.value.isValidSecret)
        assertFalse(controller.state.value.canJoin)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }

    @Test
    fun typingAJoinPrivateChannelNameDoesNotRadio() {
        var serviceReads = 0
        var radioReads = 0
        var slotReads = 0
        val fixture = ChannelFlowFixture()
        val controller = JoinPrivateChannelController(
            JoinPrivateChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    fixture.serviceProvider()
                },
                radioId = {
                    radioReads += 1
                    fixture.radioProvider()
                },
                availableSlots = {
                    slotReads += 1
                    fixture.slotsProvider()
                },
            ),
        )
        assertEquals("", controller.state.value.name)
        assertEquals("", controller.state.value.secretHex)
        assertFalse(controller.state.value.canJoin)

        controller.setName("Ops")

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, slotReads)
        assertEquals("Ops", controller.state.value.name)
        assertEquals("", controller.state.value.secretHex)
        assertFalse(controller.state.value.isValidSecret)
        assertFalse(controller.state.value.canJoin)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
    }

    @Test
    fun can_join_requires_name_valid_secret_and_no_join_in_flight() {
        val controller = controller(ChannelFlowFixture())
        assertFalse(controller.state.value.canJoin)
        controller.setName("Ops")
        controller.setSecretHex("F".repeat(ChannelSecret.SIZE_BYTES * 2))
        assertTrue(controller.state.value.canJoin)
    }

    @Test
    fun join_decodes_the_hex_and_writes_the_secret_bytes_to_the_first_free_slot() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(3, 4))
        val controller = controller(fixture)
        controller.setName("Ops")
        controller.setSecretHex("A1".repeat(ChannelSecret.SIZE_BYTES))

        var joined = false
        controller.join { joined = true }

        assertTrue(joined)
        val call = fixture.session.setChannelCalls.single()
        assertEquals(3, call.index)
        assertEquals("Ops", call.name)
        assertContentEquals(ByteArray(ChannelSecret.SIZE_BYTES) { 0xA1.toByte() }, call.secret)
    }

    @Test
    fun join_without_a_device_surfaces_no_device_connected() = runTest {
        val fixture = ChannelFlowFixture(radioId = null)
        val controller = controller(fixture)
        controller.setName("Ops")
        controller.setSecretHex("A1".repeat(ChannelSecret.SIZE_BYTES))
        var joined = false
        controller.join { joined = true }
        assertFalse(joined)
        assertEquals(ChannelActionError.NoDeviceConnected, controller.state.value.error)
        assertNull(fixture.session.setChannelCalls.firstOrNull())
    }
}
