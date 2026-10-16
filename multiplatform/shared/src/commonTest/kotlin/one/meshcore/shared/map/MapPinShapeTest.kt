package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapPinShapeTest {
    @Test
    fun nodeKindsGetDistinctShapes() {
        val chat = PinStyle.CONTACT_CHAT.mapPinShape()
        val repeater = PinStyle.CONTACT_REPEATER.mapPinShape()
        val room = PinStyle.CONTACT_ROOM.mapPinShape()
        assertEquals(MapPinShape.CHAT_TEARDROP, chat)
        assertEquals(MapPinShape.REPEATER_DIAMOND, repeater)
        assertEquals(MapPinShape.ROOM_HEXAGON, room)
        assertEquals(3, setOf(chat, repeater, room).size, "chat, repeater, and room must not share a shape")
    }

    @Test
    fun standaloneRepeaterMatchesContactRepeaterShape() {
        assertEquals(MapPinShape.REPEATER_DIAMOND, PinStyle.REPEATER.mapPinShape())
    }

    @Test
    fun droppedPinHasOwnShape() {
        assertEquals(MapPinShape.DROPPED_PIN, PinStyle.DROPPED_PIN.mapPinShape())
    }

    @Test
    fun toolOverlayStylesSkipNodeKindSilhouettes() {
        for (style in listOf(
            PinStyle.REPEATER_RING_BLUE,
            PinStyle.REPEATER_RING_GREEN,
            PinStyle.POINT_A,
            PinStyle.POINT_B,
            PinStyle.CROSSHAIR,
            PinStyle.OBSTRUCTION,
        )) {
            assertNull(style.mapPinShape(), "$style uses a dedicated overlay sprite, not a node-kind silhouette")
        }
    }
}
