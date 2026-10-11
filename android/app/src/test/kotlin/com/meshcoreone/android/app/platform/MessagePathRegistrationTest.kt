// AndroidOnly: WP-407 Actual app path-input adapters reach the incumbent canvas without borrowing another radio.
package com.meshcoreone.android.app.platform

import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.navigation.messagePathLocation
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MessagePathRegistrationTest : RoomProcessTest() {
    @Test fun outgoingZeroHopUsesTheActualConnectedRadioEndpoint() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val device = requireNotNull(h.manager.connectedDevice).copy(latitude = 37.1, longitude = -122.1)
            val flow = h.container.chatsFeature.pathDevice(device.radioId, backgroundScope)
            h.container.deviceAccess.replaceConnectedDevice(device)
            h.settle()
            val location = messagePathLocation(device.radioId, flow.value, null, false)
            val message = MessageDTO(radioId = device.radioId, text = "Synthetic outgoing", timestamp = 1u)
            val canvas = MessagePathCanvasBuilder.build(message, MessagePathArrivals.assemble(message, emptyList()), null, MessagePathDirectory(),
                location.localDevice, location.userLocation)
            assertEquals(device.id, requireNotNull(location.localDevice).id)
            assertEquals(device.publicKey, location.localDevice.publicKey)
            assertEquals(device.nodeName, location.localDevice.name)
            assertTrue(canvas.showsPathMap)
            assertTrue(canvas.points.any { it.id == device.id && it.style == MapPinStyle.POINT_A })
            assertTrue("No arrival must not invent a path or distance", canvas.selectedCoordinates.isEmpty())
            val repeat = MessageRepeatDTO(messageID = message.id, receivedAt = java.time.Instant.EPOCH, pathNodes = Bytes.EMPTY)
            val zeroHop = MessagePathCanvasBuilder.build(message, MessagePathArrivals.assemble(message, listOf(repeat)),
                null, MessagePathDirectory(), location.localDevice, location.userLocation)
            assertEquals(GeoPoint(37.1, -122.1), zeroHop.selectedCoordinates.single())
            assertTrue(zeroHop.showsPathMap)
            val otherRadio = RadioId(UUID.randomUUID())
            assertNull(h.container.chatsFeature.pathDevice(otherRadio, backgroundScope).value)
            assertNull(messagePathLocation(otherRadio, device, null, false).localDevice)
            h.manager.disconnect(); h.settle()
            assertNull(flow.value)
        } finally { h.container.close() }
    }

    @Test fun incomingPathKeepsItsLocalReceiverFinalSegmentAndDistance() {
        val radio = RadioId(UUID.randomUUID())
        val device = device(radio, 37.1, -122.1)
        val sender = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 8 }),
            name = "Synthetic sender", latitude = 37.2, longitude = -122.2, lastHeardTimestamp = null)
        val message = MessageDTO(radioId = radio, text = "Synthetic incoming", timestamp = 1u,
            direction = MessageDirection.INCOMING, senderKeyPrefix = sender.publicKeyPrefix)
        val location = messagePathLocation(radio, device, null, false)
        val canvas = MessagePathCanvasBuilder.build(message, MessagePathArrivals.assemble(message, emptyList()),
            null, MessagePathDirectory(contacts = listOf(sender)), location.localDevice, location.userLocation)
        assertEquals(listOf(GeoPoint(37.2, -122.2), GeoPoint(37.1, -122.1)), canvas.selectedCoordinates)
        assertEquals(canvas.selectedCoordinates, canvas.lines.single().points)
        assertTrue(requireNotNull(canvas.totalDistanceMeters) > 0)
        assertEquals(GeoPoint(37.1, -122.1), canvas.points.single { it.style == MapPinStyle.POINT_B }.position)
    }

    @Test fun authorizedPhoneFixIsPreferredForResolutionButNeverReplacesTheRadioEndpoint() {
        val radio = RadioId(UUID.randomUUID())
        val device = device(radio, 37.1, -122.1)
        val phone = Coordinate(38.0, -123.0)
        val granted = messagePathLocation(radio, device, phone, true)
        assertEquals(phone, granted.userLocation)
        assertEquals(GeoPoint(device.latitude, device.longitude), requireNotNull(granted.localDevice).coordinate)
        assertEquals(Coordinate(device.latitude, device.longitude), messagePathLocation(radio, device, phone, false).userLocation)
        assertEquals(phone, messagePathLocation(radio, null, phone, true).userLocation)
        assertNull(messagePathLocation(radio, null, phone, false).userLocation)
        assertNull(messagePathLocation(radio, device(radio, Double.NaN, 2.0), null, true).localDevice)
        assertNull(messagePathLocation(radio, null, Coordinate(0.0, 0.0), true).userLocation)
    }

    private fun device(radio: RadioId, latitude: Double, longitude: Double) =
        DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 3 }), nodeName = "Synthetic local",
            latitude = latitude, longitude = longitude)
}
