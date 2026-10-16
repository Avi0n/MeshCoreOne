package one.meshcore.shared.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class MapPinIconTest {
    @Test
    fun badgePointResolvesASpriteSoItNeverFallsBackToAPlainCircle() {
        // The renderer draws a plain circle for any point whose icon is empty. A midpoint SNR badge that
        // resolved to no sprite therefore dropped a phantom pin on the map.
        val icon = mapPinIconName(PinStyle.BADGE, hopIndex = null)
        assertNotEquals(MapPinIcon.NONE, icon, "a badge point must resolve a sprite, not the plain circle")
        assertEquals(MapPinIcon.BADGE, icon)
    }

    @Test
    fun hopPinsCarryTheirOrdinalInTheSpriteName() {
        assertEquals("${MapPinIcon.REPEATER_HOP_PREFIX}1", mapPinIconName(PinStyle.REPEATER_HOP, hopIndex = 1))
        assertEquals("${MapPinIcon.REPEATER_HOP_PREFIX}7", mapPinIconName(PinStyle.REPEATER_HOP, hopIndex = 7))
        assertNotEquals(
            mapPinIconName(PinStyle.REPEATER_HOP, hopIndex = 2),
            mapPinIconName(PinStyle.REPEATER_HOP, hopIndex = 3),
            "consecutive hops must not share a sprite, or route order is unreadable",
        )
    }

    @Test
    fun pathEndpointsCarryTheirOrdinalOnTheRingSprite() {
        assertEquals(
            "${MapPinIcon.REPEATER_RING_WHITE_HOP_PREFIX}4",
            mapPinIconName(PinStyle.REPEATER_RING_WHITE, hopIndex = 4),
        )
        assertEquals(
            MapPinIcon.REPEATER_RING_WHITE,
            mapPinIconName(PinStyle.REPEATER_RING_WHITE, hopIndex = null),
        )
    }

    @Test
    fun hopsClampToTheBadgeCapAndSkipNonPositiveOrdinals() {
        assertEquals(MapPinIcon.MAX_HOP_BADGE, mapPinHopBadge(PinStyle.REPEATER_HOP, MapPinIcon.MAX_HOP_BADGE + 5))
        assertEquals(
            "${MapPinIcon.REPEATER_HOP_PREFIX}${MapPinIcon.MAX_HOP_BADGE}",
            mapPinIconName(PinStyle.REPEATER_HOP, hopIndex = 900),
        )
        assertNull(mapPinHopBadge(PinStyle.REPEATER_HOP, hopIndex = 0))
        assertEquals(MapPinIcon.REPEATER, mapPinIconName(PinStyle.REPEATER_HOP, hopIndex = 0))
    }

    @Test
    fun nodeKindsAndToolOverlaysKeepTheirExistingSprites() {
        assertEquals(MapPinIcon.CHAT, mapPinIconName(PinStyle.CONTACT_CHAT))
        assertEquals(MapPinIcon.REPEATER, mapPinIconName(PinStyle.CONTACT_REPEATER))
        assertEquals(MapPinIcon.ROOM, mapPinIconName(PinStyle.CONTACT_ROOM))
        assertEquals(MapPinIcon.DROPPED, mapPinIconName(PinStyle.DROPPED_PIN))
        assertNull(mapPinHopBadge(PinStyle.CONTACT_REPEATER, hopIndex = 3))
    }

    /**
     * Line-of-sight overlays must not fall through to a plain circle: colour alone cannot tell A from
     * its assigned repeater, or the radio from an obstruction, once several marks sit on one path.
     */
    @Test
    fun lineOfSightOverlaysResolveLetteredSprites() {
        assertEquals(MapPinIcon.POINT_A, mapPinIconName(PinStyle.POINT_A))
        assertEquals(MapPinIcon.POINT_B, mapPinIconName(PinStyle.POINT_B))
        assertEquals(MapPinIcon.CROSSHAIR, mapPinIconName(PinStyle.CROSSHAIR))
        assertEquals(MapPinIcon.OBSTRUCTION, mapPinIconName(PinStyle.OBSTRUCTION))
        assertEquals(MapPinIcon.REPEATER_RING_BLUE, mapPinIconName(PinStyle.REPEATER_RING_BLUE))
        assertEquals(MapPinIcon.REPEATER_RING_GREEN, mapPinIconName(PinStyle.REPEATER_RING_GREEN))
        val overlayIcons = setOf(
            mapPinIconName(PinStyle.POINT_A),
            mapPinIconName(PinStyle.POINT_B),
            mapPinIconName(PinStyle.CROSSHAIR),
            mapPinIconName(PinStyle.OBSTRUCTION),
            mapPinIconName(PinStyle.REPEATER_RING_BLUE),
            mapPinIconName(PinStyle.REPEATER_RING_GREEN),
        )
        assertEquals(6, overlayIcons.size, "each LoS mark must own a sprite so A, B, R, X, and rings stay distinct")
        overlayIcons.forEach { icon ->
            assertNotEquals(MapPinIcon.NONE, icon)
            assertNotEquals(MapPinIcon.REPEATER, icon, "a LoS overlay must not borrow the bare repeater diamond")
        }
    }

    /**
     * A sampled fix must not borrow a node kind's silhouette: the repeater diamond on a trail claims a
     * repeater stood there. Each recency grade also needs its own sprite, or the trail loses direction.
     */
    @Test
    fun locationFixesDrawTheirOwnRecencyGradedDots() {
        assertEquals("${MapPinIcon.LOCATION_FIX_PREFIX}0", mapPinIconName(PinStyle.LOCATION_FIX, hopIndex = 0))
        assertEquals("${MapPinIcon.LOCATION_FIX_PREFIX}4", mapPinIconName(PinStyle.LOCATION_FIX, hopIndex = 4))
        assertNotEquals(
            mapPinIconName(PinStyle.LOCATION_FIX, hopIndex = 0),
            mapPinIconName(PinStyle.LOCATION_FIX, hopIndex = 4),
            "the oldest and newest dot must not share a sprite",
        )
        assertNotEquals(MapPinIcon.REPEATER, mapPinIconName(PinStyle.LOCATION_FIX, hopIndex = 2))
        assertEquals(MapPinIcon.LOCATION_LATEST, mapPinIconName(PinStyle.LOCATION_FIX_LATEST))
        assertNull(mapPinHopBadge(PinStyle.LOCATION_FIX, hopIndex = 3), "a recency grade is not a hop badge")
    }

    /** A bucket outside the ramp would name a sprite no host registers, and MapLibre then draws nothing. */
    @Test
    fun anOutOfRangeRecencyBucketClampsIntoTheRamp() {
        val last = MapPinIcon.RECENCY_BUCKET_COUNT - 1
        assertEquals(0, mapLocationFixBucket(-3))
        assertEquals(0, mapLocationFixBucket(null))
        assertEquals(last, mapLocationFixBucket(MapPinIcon.RECENCY_BUCKET_COUNT + 9))
        assertEquals("${MapPinIcon.LOCATION_FIX_PREFIX}$last", mapPinIconName(PinStyle.LOCATION_FIX, hopIndex = 99))
    }
}
