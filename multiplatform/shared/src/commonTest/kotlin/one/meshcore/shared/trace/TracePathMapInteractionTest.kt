package one.meshcore.shared.trace

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.pathedit.PathHop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracePathMapInteractionTest {
    private val alpha = repeater(1, "Alpha")
    private val bravo = repeater(2, "Bravo")
    private val charlie = repeater(3, "Charlie")
    private val repeaters = listOf(alpha, bravo, charlie)

    private fun hopFor(contact: ContactDto): PathHop =
        PathHop(hashBytes = contact.publicKey.copyOf(1), publicKey = contact.publicKey, resolvedName = contact.name)

    // MARK: pathState

    @Test
    fun pathStateMarksHopsInOrderWithOneBasedIndex() {
        val hops = listOf(hopFor(alpha), hopFor(bravo))
        val state = TracePathHopSelection.pathState(hops, repeaters)

        assertEquals(RepeaterPathInfo(inPath = true, hopIndex = 1, isLastHop = false), state[alpha.id])
        assertEquals(RepeaterPathInfo(inPath = true, hopIndex = 2, isLastHop = true), state[bravo.id])
        assertEquals(RepeaterPathInfo(inPath = false, hopIndex = null, isLastHop = false), state[charlie.id])
    }

    @Test
    fun pathStateIsEmptyMembershipWhenNoHops() {
        val state = TracePathHopSelection.pathState(emptyList(), repeaters)
        assertTrue(state.values.all { !it.inPath })
    }

    @Test
    fun pathStateIgnoresHopsWithoutAResolvedPublicKey() {
        // A hop from pasted hex that never resolved to a contact carries no publicKey, so it can't
        // mark any repeater as in-path (mirrors iOS: only `findRepeater(for:)` hits populate `pathState`).
        val unresolved = PathHop(hashBytes = byteArrayOf(0x99.toByte()))
        val state = TracePathHopSelection.pathState(listOf(unresolved), repeaters)
        assertTrue(state.values.all { !it.inPath })
    }

    // MARK: handleTap

    @Test
    fun tappingAnAbsentRepeaterAppendsIt() {
        val hops = listOf(hopFor(alpha))
        val state = TracePathHopSelection.pathState(hops, repeaters)

        assertEquals(RepeaterTapOutcome.ADDED, TracePathHopSelection.handleTap(bravo, state))
    }

    @Test
    fun tappingTheLastHopRemovesOnlyIt() {
        val hops = listOf(hopFor(alpha), hopFor(bravo))
        val state = TracePathHopSelection.pathState(hops, repeaters)

        assertEquals(RepeaterTapOutcome.REMOVED, TracePathHopSelection.handleTap(bravo, state))
    }

    @Test
    fun tappingAMiddleHopIsRejected() {
        val hops = listOf(hopFor(alpha), hopFor(bravo), hopFor(charlie))
        val state = TracePathHopSelection.pathState(hops, repeaters)

        assertEquals(RepeaterTapOutcome.REJECTED_MIDDLE_HOP, TracePathHopSelection.handleTap(alpha, state))
    }

    // MARK: candidate map builder

    @Test
    fun pointsRingTheRepeatersCurrentlyInPath() {
        val hops = listOf(hopFor(alpha))
        val state = TracePathHopSelection.pathState(hops, repeaters)
        val points = TracePathCandidateMapBuilder.points(repeaters, state, showLabels = true)

        val alphaPoint = points.first { it.id == alpha.id.value }
        val bravoPoint = points.first { it.id == bravo.id.value }
        assertEquals(PinStyle.REPEATER_RING_WHITE, alphaPoint.pinStyle)
        assertEquals(1, alphaPoint.hopIndex)
        assertEquals(PinStyle.REPEATER, bravoPoint.pinStyle)
        assertNull(bravoPoint.hopIndex)
        assertTrue(points.all { it.isClusterable })
    }

    @Test
    fun orderedHopCoordinatesFollowPathOrderNotRepeaterListOrder() {
        val hops = listOf(hopFor(bravo), hopFor(alpha))
        val coordinates = TracePathCandidateMapBuilder.orderedHopCoordinates(hops, repeaters)
        assertEquals(listOf(bravo.latitude, alpha.latitude), coordinates.map { it.latitude })
    }

    @Test
    fun orderedHopCoordinatesStartsFromTheUserLocation() {
        val hops = listOf(hopFor(alpha))
        val start = LatLng(36.5, -122.5)
        val coordinates = TracePathCandidateMapBuilder.orderedHopCoordinates(hops, repeaters, start = start)
        assertEquals(listOf(36.5, alpha.latitude), coordinates.map { it.latitude })
        val lines = TracePathCandidateMapBuilder.lines(coordinates)
        assertEquals(1, lines.size)
        assertEquals(36.5, lines[0].coordinates[0].latitude)
        assertEquals(alpha.latitude, lines[0].coordinates[1].latitude)
    }

    @Test
    fun linesConnectConsecutiveCoordinatesOnly() {
        val coordinates = TracePathCandidateMapBuilder.orderedHopCoordinates(
            listOf(hopFor(alpha), hopFor(bravo), hopFor(charlie)),
            repeaters,
        )
        val lines = TracePathCandidateMapBuilder.lines(coordinates)
        assertEquals(2, lines.size)
        assertEquals(listOf(alpha.latitude, bravo.latitude), lines[0].coordinates.map { it.latitude })
        assertEquals(listOf(bravo.latitude, charlie.latitude), lines[1].coordinates.map { it.latitude })
    }

    private fun repeater(seed: Byte, name: String): ContactDto = ContactDto(
        id = Uuid.normalized("A0000000-0000-0000-0000-00000000000$seed"),
        radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(32) { seed },
        name = name,
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 37.0 + seed,
        longitude = -122.0 - seed,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}
