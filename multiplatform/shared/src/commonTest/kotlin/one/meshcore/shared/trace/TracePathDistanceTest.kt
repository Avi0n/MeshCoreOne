package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracePathDistanceTest {
    private fun hop(
        isStartNode: Boolean = false,
        isEndNode: Boolean = false,
        latitude: Double? = null,
        longitude: Double? = null,
        name: String = "R",
    ) = TraceHop(
        hashBytes = if (isStartNode || isEndNode) null else byteArrayOf(0x31),
        resolvedName = name,
        snr = 5.0,
        isStartNode = isStartNode,
        isEndNode = isEndNode,
        latitude = latitude,
        longitude = longitude,
    )

    @Test
    fun `fewer than two hops has no distance`() {
        assertNull(totalPathDistanceMeters(listOf(hop(isStartNode = true, latitude = 1.0, longitude = 1.0))))
    }

    @Test
    fun `full path with every location present sums the legs`() {
        val hops = listOf(
            hop(isStartNode = true, latitude = 1.0, longitude = 0.5, name = "Me"),
            hop(latitude = 0.0, longitude = 1.0),
            hop(isEndNode = true, latitude = 0.0, longitude = 2.0, name = "Dest"),
        )
        val distance = totalPathDistanceMeters(hops)
        assertTrue(distance != null && distance > 0.0)
        assertFalse(isDistanceUsingDeviceLocationFallback(hops))
    }

    @Test
    fun `device without location falls back to intermediate-only distance`() {
        val hops = listOf(
            hop(isStartNode = true, latitude = null, longitude = null, name = "Me"),
            hop(latitude = 0.0, longitude = 1.0),
            hop(latitude = 0.0, longitude = 2.0),
            hop(isEndNode = true, latitude = null, longitude = null, name = "Dest"),
        )
        val distance = totalPathDistanceMeters(hops)
        assertTrue(distance != null && distance > 0.0)
        assertTrue(isDistanceUsingDeviceLocationFallback(hops))
    }

    @Test
    fun `a repeater missing location makes the distance unavailable`() {
        val hops = listOf(
            hop(isStartNode = true, latitude = 1.0, longitude = 0.5, name = "Me"),
            hop(latitude = null, longitude = null, name = "Ghost"),
            hop(isEndNode = true, latitude = 0.0, longitude = 2.0, name = "Dest"),
        )
        assertNull(totalPathDistanceMeters(hops))
        assertEquals(listOf("Ghost"), repeatersWithoutLocation(hops))
    }
}
