// PortedFrom: MC1Services/Tests/MC1ServicesTests/NodeSettingsResponseParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.cli

import com.meshcoreone.android.feature.remotenodes.cli.NodeSettingsResponseParser.ClockSyncOutcome
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swift suite "NodeSettingsResponseParser". */
@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class FeatureNodeSettingsResponseParserVectorTest(private val label: String, private val body: () -> Unit) {
    @org.junit.Test
    fun originalCase() = body()

    companion object {
        @JvmStatic
        @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = NodeSettingsResponseParserTestVectors().sourceCases().map {
            arrayOf<Any>(it.first, it.second)
        }
    }
}

private fun sourceCase(name: String, body: () -> Unit): Pair<String, () -> Unit> = name to body

private class NodeSettingsResponseParserTestVectors {
    private val parser = NodeSettingsResponseParser

    private fun case(name: String, body: () -> Unit): Pair<String, () -> Unit> =
        sourceCase("NodeSettingsResponseParserTests::$name()", body)

    fun sourceCases(): List<Pair<String, () -> Unit>> = recoveryCases() + clockAndTextCases()

    private fun recoveryCases() = listOf(
        // MARK: - Late Reply Recovery
        case("late reply recovers for the single unanswered query it parses for") {
            val recovered = parser.recoveredResponse("> 22", setOf("get tx"))
            assertEquals("get tx", recovered?.query)
            assertEquals(CLIResponse.TxPower(22), recovered?.value)

            val radio = parser.recoveredResponse("> 915.000,250.0,10,5", setOf("get tx", "get radio"))
            assertEquals("get radio", radio?.query)
            assertEquals(CLIResponse.Radio(915.0, 250.0, 10, 5), radio?.value)
        },
        case("a bare double is ambiguous when both coordinates are unanswered") {
            assertNull(parser.recoveredResponse("38.5", setOf("get lat", "get lon")))
            assertEquals(CLIResponse.Longitude(38.5), parser.recoveredResponse("38.5", setOf("get lon"))?.value)
        },
        case("query-independent shapes are never recovered") {
            for (response in listOf("OK", "ERR: not allowed", "MeshCore v1.11.0 (2025-04-18)")) {
                assertNull(parser.recoveredResponse(response, setOf("get tx")), response)
            }
        },
        case("radio CSV never recovers as TX power") {
            assertNull(parser.recoveredResponse("> 910.525,62.500,7,7", setOf("get tx")))
        },
        case("empty and free-form query sets recover nothing") {
            assertNull(parser.recoveredResponse("22", emptySet()))
            assertNull(parser.recoveredResponse("Alpha Repeater", setOf("get name")))
        },
    )

    private fun clockAndTextCases() = listOf(
        // MARK: - Device Clock
        case("Firmware clock response parses to the exact UTC date") {
            val date = assertNotNull(parser.utcDate("06:40 - 18/4/2025 UTC")).atZone(ZoneOffset.UTC)
            assertEquals(2025, date.year)
            assertEquals(4, date.monthValue)
            assertEquals(18, date.dayOfMonth)
            assertEquals(6, date.hour)
            assertEquals(40, date.minute)
        },
        case("Non-clock text returns nil") {
            assertNull(parser.utcDate("Alpha Repeater"))
            assertNull(parser.utcDate("06:40 - 18/4/2025"))
        },
        case("clock response text is extracted from a bare clock line and from an OK sync line") {
            assertEquals("06:40 - 18/4/2025 UTC", parser.clockResponseText("06:40 - 18/4/2025 UTC"))
            assertEquals("15:35 - 14/8/2026 UTC", parser.clockResponseText("OK - clock set: 15:35 - 14/8/2026 UTC"))
            assertNull(parser.clockResponseText("OK - clock set"))
            assertNull(parser.clockResponseText("Alpha Repeater"))
        },
        case("clock drift is node minus reference and nil when the text has no clock") {
            val node = assertNotNull(parser.utcDate("06:40 - 18/4/2025 UTC"))
            val now = node.plusSeconds(600)
            assertEquals(-600.0, parser.clockDrift("06:40 - 18/4/2025 UTC", now))
            assertNull(parser.clockDrift("OK - clock set", now))
        },
        // MARK: - Clock Sync
        case("Clock sync outcomes classify OK, clock-ahead, generic error, and unexpected text") {
            assertEquals(ClockSyncOutcome.Synced, parser.classifyClockSyncResponse("OK - clock set"))
            assertEquals(ClockSyncOutcome.ClockAhead, parser.classifyClockSyncResponse("ERR: clock cannot go backwards"))
            assertEquals(ClockSyncOutcome.Failed("invalid time"), parser.classifyClockSyncResponse("ERR: invalid time"))
            assertEquals(ClockSyncOutcome.Unexpected, parser.classifyClockSyncResponse("hello"))
        },
        // MARK: - Password
        case("Password change succeeds on OK or the firmware echo, fails otherwise") {
            assertTrue(parser.isPasswordChangeSuccessful("> password now: hunter2"))
            assertTrue(parser.isPasswordChangeSuccessful("OK"))
            assertFalse(parser.isPasswordChangeSuccessful("ERR: bad password"))
            assertFalse(parser.isPasswordChangeSuccessful("Alpha Repeater"))
        },
        // MARK: - Owner Info
        case("Owner info wire and display forms round-trip") {
            assertEquals("KD7ABC\nch 31", parser.displayOwnerInfo("KD7ABC|ch 31"))
            assertEquals("KD7ABC|ch 31", parser.wireOwnerInfo("KD7ABC\nch 31"))
            val display = "line one\nline two\nline three"
            assertEquals(display, parser.displayOwnerInfo(parser.wireOwnerInfo(display)))
        },
    )
}
