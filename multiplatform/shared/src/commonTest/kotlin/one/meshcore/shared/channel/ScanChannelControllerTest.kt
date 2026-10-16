package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.ChannelFloodScopeStorage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScanChannelControllerTest {
    private val secret = ByteArray(ChannelSecret.SIZE_BYTES) { 0x7C }

    private fun controller(fixture: ChannelFlowFixture) = ScanChannelController(
        ScanChannelController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            availableSlots = fixture.slotsProvider,
        ),
    )

    @Test
    fun a_valid_channel_uri_moves_to_confirmation_and_bumps_the_success_tick() {
        val controller = controller(ChannelFlowFixture())
        controller.onDecoded(ChannelUri.export("Ops", secret))
        val state = controller.state.value
        assertNotNull(state.scanned)
        assertEquals("Ops", state.scanned.name)
        assertEquals(1, state.successTick)
    }

    @Test
    fun an_invalid_code_raises_invalid_qr_format_and_keeps_scanning() {
        val controller = controller(ChannelFlowFixture())
        controller.onDecoded("not a channel uri")
        assertEquals(ScanChannelError.InvalidQrFormat, controller.state.value.error)
        assertNull(controller.state.value.scanned)
    }

    @Test
    fun denyingChannelScanCameraPermissionDoesNotRadio() = runTest {
        var serviceReads = 0
        val controller = ScanChannelController(
            ScanChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    error("channel service")
                },
                radioId = { error("radio") },
                availableSlots = { error("slots") },
            ),
        )

        controller.onPermissionDenied()

        assertEquals(0, serviceReads)
        assertEquals(true, controller.state.value.permissionDenied)
        assertNull(controller.state.value.scanned)
        assertEquals(false, controller.state.value.isJoining)
        assertNull(controller.state.value.error)
        assertEquals(0, controller.state.value.successTick)
    }

    @Test
    fun ignoringASecondChannelScanCodeDoesNotRadio() = runTest {
        var serviceReads = 0
        val controller = ScanChannelController(
            ScanChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    error("channel service")
                },
                radioId = { error("radio") },
                availableSlots = { error("slots") },
            ),
        )
        val otherSecret = ByteArray(ChannelSecret.SIZE_BYTES) { 0x11 }
        controller.onDecoded(ChannelUri.export("Ops", secret))

        controller.onDecoded(ChannelUri.export("Other", otherSecret))

        val state = controller.state.value
        val scanned = assertNotNull(state.scanned)
        assertEquals(0, serviceReads)
        assertEquals("Ops", scanned.name)
        assertContentEquals(secret, scanned.secret)
        assertEquals(1, state.successTick)
        assertEquals(false, state.isJoining)
        assertEquals(false, state.permissionDenied)
        assertNull(state.error)
    }

    @Test
    fun joiningAChannelScanWithNothingScannedDoesNotRadio() = runTest {
        var serviceReads = 0
        val controller = ScanChannelController(
            ScanChannelController.Dependencies(
                channelService = {
                    serviceReads += 1
                    error("channel service")
                },
                radioId = { error("radio") },
                availableSlots = { error("slots") },
            ),
        )

        var joined = false
        controller.join { joined = true }

        assertEquals(0, serviceReads)
        assertEquals(false, joined)
        assertNull(controller.state.value.scanned)
        assertEquals(false, controller.state.value.isJoining)
        assertNull(controller.state.value.error)
        assertEquals(0, controller.state.value.successTick)
    }

    @Test
    fun join_writes_the_scanned_name_and_secret_to_the_first_free_slot() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(4))
        val controller = controller(fixture)
        controller.onDecoded(ChannelUri.export("Ops", secret))

        var joined = false
        controller.join { joined = true }

        assertTrue(joined)
        val call = fixture.session.setChannelCalls.single()
        assertEquals(4, call.index)
        assertEquals("Ops", call.name)
        assertContentEquals(secret, call.secret)
    }

    @Test
    fun scan_again_clears_the_confirmation() {
        val controller = controller(ChannelFlowFixture())
        controller.onDecoded(ChannelUri.export("Ops", secret))
        controller.scanAgain()
        assertNull(controller.state.value.scanned)
    }

    @Test
    fun join_applies_the_scanned_region_scope_preference() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(4))
        val controller = controller(fixture)
        controller.onDecoded(ChannelUri.export("Ops", secret, ChannelFloodScope.Region("West")))

        var joined: ChannelDto? = null
        controller.join { joined = it }

        val result = assertNotNull(joined)
        assertEquals(ChannelFloodScopeStorage.MODE_SPECIFIC, result.floodScopeModeRawValue)
        assertEquals("West", result.regionScope)
    }

    @Test
    fun join_without_a_region_scope_leaves_the_channel_on_inherit() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(4))
        val controller = controller(fixture)
        controller.onDecoded(ChannelUri.export("Ops", secret))

        var joined: ChannelDto? = null
        controller.join { joined = it }

        // No region_scope means no flood-scope write, so the freshly written channel keeps its unset
        // storage (null raw mode), which resolves to the effective Inherit scope.
        assertEquals(ChannelFloodScope.Inherit, assertNotNull(joined).floodScope)
    }
}
