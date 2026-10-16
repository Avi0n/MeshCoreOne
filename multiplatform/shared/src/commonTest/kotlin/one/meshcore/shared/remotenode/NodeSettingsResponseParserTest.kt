package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Late-reply attribution, clock parsing, owner-info mapping, and success/error classification. */
class NodeSettingsResponseParserTest {
    @Test
    fun recoveredResponseAttributesTheSingleQueryItParsesFor() {
        val txPower = NodeSettingsResponseParser.recoveredResponse("> 22", setOf("get tx"))
        assertEquals("get tx", txPower?.query)
        assertEquals(CLIResponse.TxPower(22), txPower?.value)

        val radio = NodeSettingsResponseParser.recoveredResponse("> 915.000,250.0,10,5", setOf("get tx", "get radio"))
        assertEquals("get radio", radio?.query)
        assertEquals(CLIResponse.Radio(915.0, 250.0, 10, 5), radio?.value)
    }

    @Test
    fun recoveredResponseRejectsABareDoubleWhileBothCoordinatesAreUnanswered() {
        // "38.5" is a valid latitude and a valid longitude; writing it into either is a coin flip.
        assertNull(NodeSettingsResponseParser.recoveredResponse("38.5", setOf("get lat", "get lon")))

        val single = NodeSettingsResponseParser.recoveredResponse("38.5", setOf("get lon"))
        assertEquals(CLIResponse.Longitude(38.5), single?.value)
    }

    @Test
    fun recoveredResponseRejectsQueryIndependentShapes() {
        for (response in listOf("OK", "ERR: not allowed", "MeshCore v1.11.0 (2025-04-18)", "some text")) {
            assertNull(NodeSettingsResponseParser.recoveredResponse(response, setOf("get tx")))
        }
    }

    @Test
    fun recoveredResponseRejectsFreeFormQueries() {
        // `ver`, `get name` and `get owner.info` parse any text, so no reply is attributable to them.
        assertNull(NodeSettingsResponseParser.recoveredResponse("Alpha Repeater", setOf("get name")))
        assertNull(NodeSettingsResponseParser.recoveredResponse("1.11.0-letsmesh-dev", setOf("ver")))
        assertNull(NodeSettingsResponseParser.recoveredResponse("KD7ABC", setOf("get owner.info")))
        assertNull(NodeSettingsResponseParser.recoveredResponse("22", emptySet()))
    }

    @Test
    fun recoveredResponseRejectsARadioCsvAsTxPower() {
        assertNull(NodeSettingsResponseParser.recoveredResponse("> 910.525,62.500,7,7", setOf("get tx")))
    }

    @Test
    fun behaviorRepliesAreAttributedOnlyWhenOneQueryIsOutstanding() {
        // Every Behavior reply is a bare integer, so two outstanding queries make "120" ambiguous.
        // Adopting it would write a flood-hop count into the advert interval of someone's repeater.
        assertNull(
            NodeSettingsResponseParser.recoveredResponse(
                "120",
                setOf("get advert.interval", "get flood.max"),
            ),
        )

        val recovered = NodeSettingsResponseParser.recoveredResponse("120", setOf("get advert.interval"))
        assertEquals("get advert.interval", recovered?.query)
        assertEquals(CLIResponse.AdvertInterval(120), recovered?.value)
    }

    @Test
    fun clockResponseTextExtractsTheUtcShape() {
        assertEquals(
            "06:40 - 18/4/2025 UTC",
            NodeSettingsResponseParser.clockResponseText("06:40 - 18/4/2025 UTC"),
        )
        assertEquals(
            "15:35 - 14/8/2026 UTC",
            NodeSettingsResponseParser.clockResponseText("OK - clock set: 15:35 - 14/8/2026 UTC"),
        )
        assertNull(NodeSettingsResponseParser.clockResponseText("OK - clock set"))
        assertNull(NodeSettingsResponseParser.clockResponseText("Alpha Repeater"))
    }

    @Test
    fun utcEpochSecondsParsesClockResponse() {
        // 2025-04-18 06:40:00 UTC
        assertEquals(1_744_958_400L, NodeSettingsResponseParser.utcEpochSeconds("06:40 - 18/4/2025 UTC"))
        assertNull(NodeSettingsResponseParser.utcEpochSeconds("no clock here"))
    }

    @Test
    fun clockDriftSecondsIsNodeMinusNow() {
        val node = 1_744_958_400L
        assertEquals(300.0, NodeSettingsResponseParser.clockDriftSeconds("06:40 - 18/4/2025 UTC", node - 300))
        assertEquals(-60.0, NodeSettingsResponseParser.clockDriftSeconds("06:40 - 18/4/2025 UTC", node + 60))
        assertNull(NodeSettingsResponseParser.clockDriftSeconds("no clock here", node))
    }

    @Test
    fun classifyClockSync() {
        assertIs<NodeSettingsResponseParser.ClockSyncOutcome.Synced>(
            NodeSettingsResponseParser.classifyClockSyncResponse("OK"),
        )
        assertIs<NodeSettingsResponseParser.ClockSyncOutcome.Synced>(
            NodeSettingsResponseParser.classifyClockSyncResponse("OK - clock set"),
        )
        assertIs<NodeSettingsResponseParser.ClockSyncOutcome.ClockAhead>(
            NodeSettingsResponseParser.classifyClockSyncResponse("ERR: clock cannot go backwards"),
        )
        val failed = NodeSettingsResponseParser.classifyClockSyncResponse("ERR: some other problem")
        assertIs<NodeSettingsResponseParser.ClockSyncOutcome.Failed>(failed)
        assertEquals("some other problem", failed.message)
        assertIs<NodeSettingsResponseParser.ClockSyncOutcome.Unexpected>(
            NodeSettingsResponseParser.classifyClockSyncResponse("915.0,250.0,10,5"),
        )
    }

    @Test
    fun passwordChangeSuccessClassification() {
        assertTrue(NodeSettingsResponseParser.isPasswordChangeSuccessful("OK"))
        assertTrue(NodeSettingsResponseParser.isPasswordChangeSuccessful("password now: hunter2"))
        // A password carrying both ":" and "/" echoes back in the same shape as a clock reply; the node
        // accepted it, so the screen must not report the change as failed.
        assertTrue(NodeSettingsResponseParser.isPasswordChangeSuccessful("password now: a/b:c"))
        assertTrue(!NodeSettingsResponseParser.isPasswordChangeSuccessful("ERR: nope"))
    }

    @Test
    fun ownerInfoWireAndDisplayMapping() {
        assertEquals("line1\nline2", NodeSettingsResponseParser.displayOwnerInfo("line1|line2"))
        assertEquals("line1|line2", NodeSettingsResponseParser.wireOwnerInfo("line1\nline2"))
    }
}
