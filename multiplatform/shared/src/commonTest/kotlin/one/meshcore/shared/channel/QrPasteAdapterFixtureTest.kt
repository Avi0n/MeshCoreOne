package one.meshcore.shared.channel

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreUrlParser
import one.meshcore.shared.remotenode.MAX_REGION_NAME_BYTES
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Channel QR text enters through [ScanChannelController.onDecoded]. A join reaches the session only
 * after the user confirms, and a bad code never confirms.
 */
class QrPasteAdapterFixtureTest {
    private val secret = ByteArray(MeshCoreUrlParser.CHANNEL_SECRET_SIZE_BYTES) { SECRET_FILL.toByte() }

    @Test
    fun malformedAndOversizedChannelCodesDoNotJoin() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(PRIVATE_SLOT))
        val controller = controller(fixture)
        val validHex = secret.toUppercaseHex()

        controller.onDecoded("meshcore://channel/add?name=Ops%ZZ&secret=$validHex")
        controller.onDecoded(oversizedSecretUrl())
        controller.onDecoded("x".repeat(HOSTILE_PAYLOAD_CHARS))

        var joined = false
        controller.join { joined = true }

        assertEquals(false, joined)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
        assertNull(controller.state.value.scanned)
        assertEquals(ScanChannelError.InvalidQrFormat, controller.state.value.error)
        assertEquals(0, controller.state.value.successTick)
        assertEquals(false, controller.state.value.isJoining)
    }

    @Test
    fun anOversizedRegionStaysOffTheRadioUntilConfirm() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(PRIVATE_SLOT))
        val controller = controller(fixture)
        val region = "R".repeat(MAX_REGION_NAME_BYTES + REGION_OVERSHOOT_BYTES)
        val url = "meshcore://channel/add?name=Ops&secret=${secret.toUppercaseHex()}&region_scope=$region"

        controller.onDecoded(url)

        val scanned = assertNotNull(controller.state.value.scanned)
        assertEquals(MAX_REGION_NAME_BYTES, scanned.regionScope?.encodeToByteArray()?.size)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
        assertEquals(false, controller.state.value.isJoining)
    }

    @Test
    fun anOversizedPrivateNameIsCutBeforeTheSessionWrite() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(PRIVATE_SLOT))
        val controller = controller(fixture)
        val name = "N".repeat(MAX_USABLE_NAME_BYTES + NAME_OVERSHOOT_BYTES)

        controller.onDecoded("meshcore://channel/add?name=$name&secret=${secret.toUppercaseHex()}")
        assertEquals(name.length, controller.state.value.scanned?.name?.length)
        var joined = false
        controller.join { joined = true }

        val call = fixture.session.setChannelCalls.single()
        assertEquals(true, joined)
        assertEquals(PRIVATE_SLOT, call.index)
        assertEquals(MAX_USABLE_NAME_BYTES, call.name.encodeToByteArray().size)
        assertEquals("N".repeat(MAX_USABLE_NAME_BYTES), call.name)
    }

    @Test
    fun aSecondCodeAndALostRadioDoNotWrite() = runTest {
        var radio: Uuid? = ChannelTestIds.RADIO
        val fixture = ChannelFlowFixture(availableSlots = listOf(PRIVATE_SLOT))
        val controller = ScanChannelController(
            ScanChannelController.Dependencies(
                channelService = { fixture.service },
                radioId = { radio },
                availableSlots = { listOf(PRIVATE_SLOT) },
            ),
        )
        val otherSecret = ByteArray(MeshCoreUrlParser.CHANNEL_SECRET_SIZE_BYTES) { OTHER_SECRET_FILL.toByte() }
        controller.onDecoded(ChannelUri.export("Ops", secret))
        controller.onDecoded(ChannelUri.export("Other", otherSecret))
        assertEquals("Ops", controller.state.value.scanned?.name)

        radio = null
        var joined = false
        controller.join { joined = true }

        assertEquals(false, joined)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
        assertEquals(
            ScanChannelError.Join(ChannelActionError.NoDeviceConnected),
            controller.state.value.error,
        )
        assertEquals(false, controller.state.value.isJoining)
    }

    @Test
    fun aHashtagCodeAndADeniedCameraDoNotJoin() = runTest {
        val fixture = ChannelFlowFixture(availableSlots = listOf(PRIVATE_SLOT))
        val controller = controller(fixture)

        controller.onPermissionDenied()
        controller.onDecoded("meshcore://channel/add?name=%23ops")

        assertEquals(true, controller.state.value.permissionDenied)
        assertEquals("#ops", controller.state.value.scanned?.name)
        assertTrue(fixture.session.setChannelCalls.isEmpty())
        assertEquals(false, controller.state.value.isJoining)
    }

    private fun controller(fixture: ChannelFlowFixture) = ScanChannelController(
        ScanChannelController.Dependencies(
            channelService = fixture.serviceProvider,
            radioId = fixture.radioProvider,
            availableSlots = fixture.slotsProvider,
        ),
    )

    private fun oversizedSecretUrl(): String {
        val hex = "AB".repeat(MeshCoreUrlParser.CHANNEL_SECRET_SIZE_BYTES + 1)
        return "meshcore://channel/add?name=Ops&secret=$hex"
    }

    private companion object {
        const val PRIVATE_SLOT = 1
        const val SECRET_FILL = 0x44
        const val OTHER_SECRET_FILL = 0x11
        const val REGION_OVERSHOOT_BYTES = 8
        const val NAME_OVERSHOOT_BYTES = 8
        const val HOSTILE_PAYLOAD_CHARS = 8192
    }
}
