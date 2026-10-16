package one.meshcore.shared.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapFilterStorageTest {
    @Test
    fun legacyPreferencesAndNullKeysKeepTheirExistingWhoSelections() {
        val raw = """{"favoritesOnly":true,"showChat":false,"lastHeard":null,"hops":null} """
        val state = assertNotNull(MapFilterState.fromStorageString(raw))
        assertTrue(state.favoritesOnly)
        assertFalse(state.showChat)
        assertEquals(MapLastHeardRange.ANY, state.lastHeard)
        assertEquals(MapHopRange.ANY, state.hops)
        assertTrue(assertNotNull(MapFilterState.fromStorageString("{\"showChat\":null}")).showChat)
    }

    @Test
    fun malformedRangesAndIndividualBoundsDoNotDiscardOtherSelections() {
        val first = assertNotNull(
            MapFilterState.fromStorageString(
                """{"showRoom":false,"lastHeard":[],"hops":{"min":"1","max":2}}""",
            ),
        )
        assertFalse(first.showRoom)
        assertEquals(MapLastHeardRange.ANY, first.lastHeard)
        assertEquals(MapHopRange(max = 2), first.hops)
        val second = assertNotNull(
            MapFilterState.fromStorageString(
                """{"lastHeard":{"minAge":{"value":"1","unit":"hours"},"maxAge":{"value":2,"unit":"days"}},"hops":false}""",
            ),
        )
        assertNull(second.lastHeard.minAge)
        assertEquals(MapDuration(2, MapDurationUnit.DAYS), second.lastHeard.maxAge)
        assertEquals(MapHopRange.ANY, second.hops)
    }

    @Test
    fun validSwiftLongIntegersClampBeforeNarrowing() {
        val state = assertNotNull(
            MapFilterState.fromStorageString(
                """{"lastHeard":{"maxAge":{"value":2147483648,"unit":"minutes"}},"hops":{"min":-2147483649,"max":2147483648}}""",
            ),
        )
        assertEquals(MapDuration(9_999, MapDurationUnit.MINUTES), state.lastHeard.maxAge)
        assertEquals(MapHopRange.ANY, state.hops)
    }

    @Test
    fun integralDecimalAndExponentNumbersDecodeLikeSwift() {
        val state = assertNotNull(
            MapFilterState.fromStorageString(
                """{"lastHeard":{"maxAge":{"value":1e0,"unit":"hours"}},"hops":{"min":1.0,"max":2.0}}""",
            ),
        )
        assertEquals(MapDuration(1, MapDurationUnit.HOURS), state.lastHeard.maxAge)
        assertEquals(MapHopRange(1, 2), state.hops)
        val negative = assertNotNull(
            MapFilterState.fromStorageString(
                """{"lastHeard":{"maxAge":{"value":-9223372036854775807.0,"unit":"hours"}}}""",
            ),
        )
        assertEquals(MapDuration(1, MapDurationUnit.HOURS), negative.lastHeard.maxAge)
    }

    @Test
    fun malformedWhoBooleansStillRejectTheWholePreference() {
        for (value in listOf("\"true\"", "1", "[]", "{}")) {
            assertNull(MapFilterState.fromStorageString("""{"showDiscovered":$value,"hops":{"max":2}}"""))
        }
    }

    @Test
    fun rangesRoundTripWithSwiftKeysAndOmittedOptionalBounds() {
        val state = MapFilterState(
            showDiscovered = true,
            lastHeard = MapLastHeardRange(
                MapDuration(15, MapDurationUnit.MINUTES),
                MapDuration(1, MapDurationUnit.DAYS),
            ),
            hops = MapHopRange(1, 3),
        )
        assertEquals(state, MapFilterState.fromStorageString(state.toStorageString()))
        val any = Json.parseToJsonElement(MapFilterState().toStorageString()).jsonObject
        assertTrue(any.getValue("lastHeard").jsonObject.isEmpty())
        assertEquals(setOf("min"), any.getValue("hops").jsonObject.keys)
    }
}
