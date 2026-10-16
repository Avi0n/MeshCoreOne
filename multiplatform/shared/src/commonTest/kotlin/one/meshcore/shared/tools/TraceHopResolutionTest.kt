package one.meshcore.shared.tools

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.meshcore.TraceNode
import one.meshcore.shared.pathedit.PathEditFixtures
import one.meshcore.shared.trace.TraceFailureMessages
import one.meshcore.shared.trace.TracePathMapBuilder
import one.meshcore.shared.trace.TracePathRequest
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.trace.isDistanceUsingDeviceLocationFallback
import one.meshcore.shared.trace.runTracePath
import one.meshcore.shared.trace.totalPathDistanceMeters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import one.meshcore.shared.pathedit.PathHop as EditableHop

/**
 * The trace result the runner produces is the input here — no hand-built hop fixture — so the first
 * assertions record the defect exactly as it reaches the screen: every hop location-less, distance
 * unavailable, result overlay empty.
 */
class TraceHopResolutionTest {
    private val summit = located(name = "Summit", keyPrefix = 0x11, latitude = 37.0, longitude = -122.0)
    private val ridge = located(name = "Ridge", keyPrefix = 0x22, latitude = 37.2, longitude = -122.3)

    @Test
    fun aRealTraceGainsHopCoordinatesAndFeedsDistanceAndTheMap() = runTest {
        val raw = traceOverBothRepeaters()

        assertTrue(raw.success)
        assertTrue(raw.hops.none { it.hasLocation }, "the runner cannot resolve a node from wire bytes")
        assertNull(totalPathDistanceMeters(raw.hops))
        assertTrue(TracePathMapBuilder.buildFromResult(raw).points.isEmpty())

        val resolved = resolveTraceHops(
            result = raw,
            outboundPath = outboundPath(),
            contacts = listOf(summit, ridge),
            discovered = emptyList(),
            deviceLocation = null,
        )

        val intermediates = resolved.hops.filter { !it.isStartNode && !it.isEndNode }
        assertEquals(2, intermediates.size)
        assertTrue(intermediates.all { it.hasLocation })
        assertEquals(37.0, intermediates[0].latitude)
        assertEquals(-122.0, intermediates[0].longitude)
        assertEquals("Summit", intermediates[0].resolvedName)
        assertEquals("Ridge", intermediates[1].resolvedName)

        val distance = totalPathDistanceMeters(resolved.hops)
        assertNotNull(distance, "two located repeaters give the intermediate-only distance")
        assertTrue(distance > 0.0)

        val plot = TracePathMapBuilder.buildFromResult(resolved)
        assertTrue(plot.points.isNotEmpty(), "a successful trace must draw its overlay")
        assertTrue(plot.lines.isNotEmpty())
    }

    /**
     * The start and end hops are the local device on both ends of the trace, so without its position the
     * total distance can only sum the repeaters in between and the map plots neither device pin nor the
     * legs to and from them. Mirrors iOS stamping `bestAvailableLocation` onto both.
     */
    @Test
    fun theDeviceLocationLandsOnTheStartAndEndHopsAndCompletesTheDistance() = runTest {
        val raw = traceOverBothRepeaters()
        val contacts = listOf(summit, ridge)

        val withoutDevice = resolveTraceHops(raw, outboundPath(), contacts, emptyList(), deviceLocation = null)
        val withDevice = resolveTraceHops(raw, outboundPath(), contacts, emptyList(), deviceLocation = DEVICE)

        val start = withDevice.hops.first()
        val end = withDevice.hops.last()
        assertTrue(start.isStartNode && end.isEndNode)
        assertEquals(DEVICE.latitude, start.latitude)
        assertEquals(DEVICE.longitude, start.longitude)
        assertEquals(DEVICE.latitude, end.latitude)
        assertEquals(DEVICE.longitude, end.longitude)

        assertTrue(
            isDistanceUsingDeviceLocationFallback(withoutDevice.hops),
            "with no device position the figure can only cover the repeaters between the ends",
        )
        assertFalse(isDistanceUsingDeviceLocationFallback(withDevice.hops))

        val repeatersOnly = assertNotNull(totalPathDistanceMeters(withoutDevice.hops))
        val fullPath = assertNotNull(totalPathDistanceMeters(withDevice.hops))
        assertTrue(fullPath > repeatersOnly, "the device-to-first-hop and last-hop-to-device legs are real distance")

        val plot = TracePathMapBuilder.buildFromResult(withDevice)
        val hopPins = plot.points.filter { it.id.startsWith("trace-hop-") }
        assertEquals(4, hopPins.size, "both device pins join the two repeaters")
        assertEquals(
            3,
            plot.lines.size,
            "the device-to-hop1 and hopN-to-device legs are drawn like the leg between the repeaters",
        )
        assertEquals(
            1,
            TracePathMapBuilder.buildFromResult(withoutDevice).lines.size,
            "without the device position the overlay is the single leg between the two repeaters",
        )
    }

    /** A phone with no fix and a radio with no coordinates leaves both ends unplaced, as before. */
    @Test
    fun noDeviceLocationLeavesTheEndsUnplaced() = runTest {
        val resolved = resolveTraceHops(
            result = traceOverBothRepeaters(),
            outboundPath = outboundPath(),
            contacts = listOf(summit, ridge),
            discovered = emptyList(),
            deviceLocation = null,
        )
        assertTrue(resolved.hops.first().let { it.isStartNode && !it.hasLocation })
        assertTrue(resolved.hops.last().let { it.isEndNode && !it.hasLocation })
    }

    @Test
    fun aHopWithNoKnownNodeKeepsItsHexAndStaysLocationLess() = runTest {
        val resolved = resolveTraceHops(
            result = traceOverBothRepeaters(),
            outboundPath = emptyList(),
            contacts = listOf(summit),
            discovered = emptyList(),
            deviceLocation = null,
        )
        val ridgeHop = resolved.hops.single { it.hashDisplayString == "22" }
        assertNull(ridgeHop.resolvedName)
        assertTrue(!ridgeHop.hasLocation)
    }

    /**
     * At a 1-byte hash a prefix collision between known repeaters is ordinary, not rare. iOS resolves the
     * hop through `RepeaterResolver.bestMatch` — nearest, then freshest advert — so the user sees a name;
     * refusing on ambiguity would print bare hex on a hop the app can identify.
     */
    @Test
    fun aCollidingPrefixResolvesToTheFreshestAdvertRatherThanBareHex() = runTest {
        val stale = PathEditFixtures.contact(
            name = "Alpha",
            publicKey = PathEditFixtures.key(0x11),
            id = PathEditFixtures.uuid(41),
        ).copy(lastAdvertTimestamp = STALE_ADVERT)
        val fresh = PathEditFixtures.contact(
            name = "Bravo",
            publicKey = PathEditFixtures.key(0x11, 0x99),
            id = PathEditFixtures.uuid(42),
        ).copy(lastAdvertTimestamp = FRESH_ADVERT)

        // No outbound hop carries the full key, so the hash itself is all the resolver has to go on.
        val resolved = resolveTraceHops(
            traceOverBothRepeaters(),
            outboundPath = emptyList(),
            contacts = listOf(stale, fresh),
            discovered = emptyList(),
            deviceLocation = null,
        )

        val hop = resolved.hops.first { it.hashBytes?.firstOrNull() == 0x11.toByte() }
        assertEquals("Bravo", hop.resolvedName)
    }

    /**
     * With a user fix, proximity beats advert recency — the same order iOS `RepeaterResolver.bestMatch`
     * uses on a completed trace hop.
     */
    @Test
    fun aCollidingPrefixPicksTheNearestNodeWhenTheDeviceHasAFix() = runTest {
        val far = PathEditFixtures.contact(
            name = "Far",
            publicKey = PathEditFixtures.key(0x11),
            id = PathEditFixtures.uuid(41),
        ).copy(latitude = 40.0, longitude = -122.0, lastAdvertTimestamp = FRESH_ADVERT)
        val near = PathEditFixtures.contact(
            name = "Near",
            publicKey = PathEditFixtures.key(0x11, 0x99),
            id = PathEditFixtures.uuid(42),
        ).copy(latitude = DEVICE.latitude, longitude = DEVICE.longitude, lastAdvertTimestamp = STALE_ADVERT)

        val resolved = resolveTraceHops(
            traceOverBothRepeaters(),
            outboundPath = emptyList(),
            contacts = listOf(far, near),
            discovered = emptyList(),
            deviceLocation = DEVICE,
        )

        val hop = resolved.hops.first { it.hashBytes?.firstOrNull() == 0x11.toByte() }
        assertEquals("Near", hop.resolvedName)
    }

    private fun outboundPath(): List<EditableHop> = listOf(summit, ridge).map { contact ->
        EditableHop(
            hashBytes = contact.publicKey.copyOf(1),
            publicKey = contact.publicKey,
            resolvedName = null,
        )
    }

    private suspend fun traceOverBothRepeaters(): TraceResult {
        val events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 4)
        return runTracePath(
            TracePathRequest(
                pathBytes = byteArrayOf(0x11, 0x22),
                hashSize = 1,
                flags = 0,
                events = events,
                sendTrace = { sentTag, _, _ ->
                    events.tryEmit(
                        MeshEvent.TraceData(
                            TraceInfo(
                                tag = sentTag,
                                authCode = 0,
                                flags = 0,
                                pathLength = 2,
                                path = listOf(
                                    TraceNode(hashBytes = byteArrayOf(0x11), snr = 4.0),
                                    TraceNode(hashBytes = byteArrayOf(0x22), snr = -0.5),
                                ),
                            ),
                        ),
                    )
                    MessageSentInfo(route = 0, expectedAck = ByteArray(0), suggestedTimeoutMs = 5_000)
                },
                deviceName = "Radio",
                failureMessages = TraceFailureMessages("fail", "send fail"),
                nowMs = { 1_000L },
                randomTag = { 7L },
            ),
        )
    }

    private fun located(name: String, keyPrefix: Int, latitude: Double, longitude: Double): ContactDto =
        PathEditFixtures.contact(
            name = name,
            publicKey = PathEditFixtures.key(keyPrefix),
            id = PathEditFixtures.uuid(keyPrefix),
        ).copy(latitude = latitude, longitude = longitude)

    private companion object {
        /** Off the line through both repeaters, so the two device legs add real distance. */
        val DEVICE = LatLng(latitude = 37.4, longitude = -121.8)
        const val STALE_ADVERT = 10L
        const val FRESH_ADVERT = 99L
    }
}
