package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.hopCount
import one.meshcore.shared.chat.isDirectRouted
import one.meshcore.shared.chat.isFloodRouted
import one.meshcore.shared.chat.pathNodesHex
import one.meshcore.shared.chat.pathString
import one.meshcore.shared.chat.pathStringForClipboard
import one.meshcore.shared.model.RouteType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the message routing/path accessors ported from the Swift `Message` computed properties: the
 * flood-routing priority (channel → routeType → pathLength sentinel), the hop-count decode, and the display
 * path strings. Also pins the "Direct means routing mode, not zero hops" semantic.
 */
class MessagePathAccessorsTest {
    private val floodSentinel = 0xFF

    @Test
    fun channelMessageIsAlwaysFloodRouted() {
        val message = ChatFixtures.message(channelIndex = 0).copy(pathLength = 3, routeType = RouteType.DIRECT)
        assertTrue(message.isFloodRouted)
    }

    @Test
    fun routeTypeDecidesFloodForDirectMessages() {
        val dm = ChatFixtures.message(channelIndex = null)
        assertTrue(dm.copy(routeType = RouteType.FLOOD).isFloodRouted)
        assertTrue(dm.copy(routeType = RouteType.TC_FLOOD).isFloodRouted)
        assertFalse(dm.copy(routeType = RouteType.DIRECT).isFloodRouted)
        assertFalse(dm.copy(routeType = RouteType.TC_DIRECT).isFloodRouted)
    }

    @Test
    fun pathLengthSentinelDecidesFloodWhenRouteTypeAbsent() {
        val dm = ChatFixtures.message(channelIndex = null).copy(routeType = null)
        assertTrue(dm.copy(pathLength = 3).isFloodRouted) // a real path length is not the sentinel
        assertFalse(dm.copy(pathLength = floodSentinel).isFloodRouted)
    }

    @Test
    fun hopCountDecodesFromPathLengthLowerBits() {
        val message = ChatFixtures.message().copy(pathLength = 0x43) // mode 1, 3 hops
        assertEquals(3, message.hopCount)
    }

    @Test
    fun directRoutedMessageStillCarriesHops() {
        // "Direct" is the out-path routing mode, not a zero-hop path: a direct-routed message can have hops.
        val message = ChatFixtures.message(channelIndex = null)
            .copy(routeType = RouteType.DIRECT, pathLength = 0x02, pathNodes = byteArrayOf(0xA3.toByte(), 0x7F))
        assertTrue(message.isDirectRouted)
        assertEquals(2, message.hopCount)
        assertEquals(listOf("A3", "7F"), message.pathNodesHex)
    }

    @Test
    fun pathStringsRenderArrowAndCommaJoined() {
        val message = ChatFixtures.message()
            .copy(pathLength = 0x03, pathNodes = byteArrayOf(0xA3.toByte(), 0x7F, 0x42))
        assertEquals("A3 → 7F → 42", message.pathString)
        assertEquals("A3,7F,42", message.pathStringForClipboard)
    }

    @Test
    fun noPathNodesYieldEmptyPathStrings() {
        val message = ChatFixtures.message().copy(pathNodes = null)
        assertTrue(message.pathNodesHex.isEmpty())
        assertEquals("", message.pathString)
    }
}
