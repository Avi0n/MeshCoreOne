// PortedFrom: MC1Tests/Protocol/CLIResponseTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.cli

import com.meshcoreone.android.feature.remotenodes.cli.CLIResponse.Companion.parse
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** App-target suite `CLIResponseTests` (MC1Tests/Protocol). */
@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class FeatureCLIResponseProtocolVectorTest(private val label: String, private val body: () -> Unit) {
    @org.junit.Test
    fun originalCase() = body()

    companion object {
        @JvmStatic
        @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = CLIResponseProtocolTestVectors().sourceCases().map {
            arrayOf<Any>(it.first, it.second)
        }
    }
}

private fun sourceCase(name: String, body: () -> Unit): Pair<String, () -> Unit> = name to body

private class CLIResponseProtocolTestVectors {
    private fun case(name: String, body: () -> Unit): Pair<String, () -> Unit> =
        sourceCase("CLIResponseTests::$name()", body)

    private fun assertRadio(result: CLIResponse, frequency: Double, bandwidth: Double, sf: Long, cr: Long) {
        val radio = assertIs<CLIResponse.Radio>(result, "Expected .radio, got $result")
        assertTrue(abs(radio.frequency - frequency) < 0.0001)
        assertTrue(abs(radio.bandwidth - bandwidth) < 0.01)
        assertEquals(sf, radio.spreadingFactor)
        assertEquals(cr, radio.codingRate)
    }

    private fun assertLatitude(result: CLIResponse, expected: Double) =
        assertTrue(abs(assertIs<CLIResponse.Latitude>(result, "Expected .latitude, got $result").value - expected) < 0.0001)

    private fun assertLongitude(result: CLIResponse, expected: Double) =
        assertTrue(abs(assertIs<CLIResponse.Longitude>(result, "Expected .longitude, got $result").value - expected) < 0.0001)

    fun sourceCases(): List<Pair<String, () -> Unit>> = promptCases() + valueCases() + freeFormCases() + edgeCases()

    private fun promptCases() = listOf(
        // MARK: - Prompt Prefix Stripping
        case("parse prompt prefix strips prefix") { assertEquals(CLIResponse.Ok, parse("> OK")) },
        case("parse no prompt prefix still works") { assertEquals(CLIResponse.Ok, parse("OK")) },
        case("parse prompt prefix with whitespace") { assertEquals(CLIResponse.Ok, parse("  > OK  ")) },
        case("parse ok with message clock set") { assertEquals(CLIResponse.Ok, parse("OK - clock set: 16:13 - 9/1/2026 UTC")) },
        case("parse ok with message with prompt prefix") {
            assertEquals(CLIResponse.Ok, parse("> OK - clock set: 16:13 - 9/1/2026 UTC"))
        },
        // MARK: - Radio with Prompt
        case("parse radio with prompt prefix") { assertRadio(parse("> 910.5250244,62.5,7,8", "get radio"), 910.5250244, 62.5, 7, 8) },
        case("parse radio without prompt prefix") { assertRadio(parse("915.000,250.0,10,5", "get radio"), 915.0, 250.0, 10, 5) },
        // MARK: - Name with Prompt
        case("parse name with prompt prefix") {
            assertEquals(CLIResponse.Name("Sunnyslope Repeater"), parse("> Sunnyslope Repeater", "get name"))
        },
        case("parse name without prompt prefix") { assertEquals(CLIResponse.Name("My Node"), parse("My Node", "get name")) },
        // MARK: - Repeat Mode with Prompt
        case("parse repeat mode on with prompt prefix") { assertEquals(CLIResponse.RepeatMode(true), parse("> on", "get repeat")) },
        case("parse repeat mode off with prompt prefix") { assertEquals(CLIResponse.RepeatMode(false), parse("> off", "get repeat")) },
        case("parse repeat mode without prompt prefix") { assertEquals(CLIResponse.RepeatMode(true), parse("on", "get repeat")) },
    )

    private fun valueCases() = listOf(
        // MARK: - TX Power with Prompt
        case("parse tx power with prompt prefix") { assertEquals(CLIResponse.TxPower(22), parse("> 22", "get tx")) },
        case("parse tx power without prompt prefix") { assertEquals(CLIResponse.TxPower(17), parse("17", "get tx")) },
        case("parse ZephCore tx power with apc off suffix") {
            assertEquals(CLIResponse.TxPower(22), parse("> 22dBm (apc=off)", "get tx"))
        },
        case("parse ZephCore tx power with apc on uses configured ceiling") {
            assertEquals(CLIResponse.TxPower(22), parse("> 16dBm (apc=on max=22 reduction=6 margin=18.5 target=16)", "get tx"))
        },
        // MARK: - Coordinates with Prompt
        case("parse latitude with prompt prefix") { assertLatitude(parse("> 33.4484", "get lat"), 33.4484) },
        case("parse latitude without prompt prefix") { assertLatitude(parse("40.7128", "get lat"), 40.7128) },
        case("parse longitude with prompt prefix") { assertLongitude(parse("> -112.0740", "get lon"), -112.0740) },
        case("parse longitude without prompt prefix") { assertLongitude(parse("-74.0060", "get lon"), -74.0060) },
        // MARK: - Intervals with Prompt
        case("parse advert interval with prompt prefix") { assertEquals(CLIResponse.AdvertInterval(5), parse("> 5", "get advert.interval")) },
        case("parse advert interval without prompt prefix") { assertEquals(CLIResponse.AdvertInterval(10), parse("10", "get advert.interval")) },
        case("parse flood advert interval with prompt prefix") {
            assertEquals(CLIResponse.FloodAdvertInterval(12), parse("> 12", "get flood.advert.interval"))
        },
        case("parse flood advert interval without prompt prefix") {
            assertEquals(CLIResponse.FloodAdvertInterval(6), parse("6", "get flood.advert.interval"))
        },
        case("parse flood max with prompt prefix") { assertEquals(CLIResponse.FloodMax(3), parse("> 3", "get flood.max")) },
        case("parse flood max without prompt prefix") { assertEquals(CLIResponse.FloodMax(4), parse("4", "get flood.max")) },
        // MARK: - Error with Prompt
        case("parse error with prompt prefix") {
            assertEquals("Error: permission denied", assertIs<CLIResponse.Error>(parse("> Error: permission denied")).message)
        },
        case("parse error without prompt prefix") {
            assertEquals("Error: invalid value", assertIs<CLIResponse.Error>(parse("Error: invalid value")).message)
        },
        case("parse unknown command with prompt prefix") {
            assertEquals(CLIResponse.UnknownCommand("Error: unknown command"), parse("> Error: unknown command"))
        },
        // MARK: - Version with Prompt
        case("parse version with prompt prefix") {
            assertEquals(CLIResponse.Version("MeshCore v1.10.0 (2025-04-18)"), parse("> MeshCore v1.10.0 (2025-04-18)"))
        },
        case("parse version without prompt prefix") {
            assertEquals(CLIResponse.Version("MeshCore v1.11.0 (2025-05-01)"), parse("MeshCore v1.11.0 (2025-05-01)"))
        },
        case("parse version short format with prompt prefix") {
            assertEquals(CLIResponse.Version("v1.12.0 (2025-06-15)"), parse("> v1.12.0 (2025-06-15)"))
        },
        case("parse version non standard format with query hint") {
            val text = "1.11.0-letsmesh.net-dev-2026-01-06-09005fa (Build: 06-Jan-2026)"
            assertEquals(CLIResponse.Version(text), parse(text, "ver"))
        },
        // MARK: - Device Time with Prompt
        case("parse device time with prompt prefix") {
            assertEquals(CLIResponse.DeviceTime("06:40 - 18/4/2025 UTC"), parse("> 06:40 - 18/4/2025 UTC", "clock"))
        },
        case("parse device time without prompt prefix") {
            assertEquals(CLIResponse.DeviceTime("14:30 - 25/12/2025 UTC"), parse("14:30 - 25/12/2025 UTC", "clock"))
        },
    )

    private fun freeFormCases() = listOf(
        // MARK: - Owner Info
        case("parse owner info plain text") { assertEquals(CLIResponse.OwnerInfo("some|pipe|text"), parse("some|pipe|text", "get owner.info")) },
        case("parse owner info with prompt prefix") {
            assertEquals(CLIResponse.OwnerInfo("915MHz|KD7ABC|example.com"), parse("> 915MHz|KD7ABC|example.com", "get owner.info"))
        },
        case("parse owner info empty") { assertEquals(CLIResponse.OwnerInfo(""), parse("", "get owner.info")) },
        // Firmware returns just "> " when owner.info is empty.
        case("parse owner info bare prompt") { assertEquals(CLIResponse.OwnerInfo(""), parse("> ", "get owner.info")) },
        case("parse owner info single line") { assertEquals(CLIResponse.OwnerInfo("just a name"), parse("just a name", "get owner.info")) },
        // Contact info with ":" and "/" was misclassified as deviceTime.
        case("parse owner info with colon and slash") {
            assertEquals(CLIResponse.OwnerInfo("Contact: KD7ABC / 145.230"), parse("> Contact: KD7ABC / 145.230", "get owner.info"))
        },
        // Name with ":" and "/" was misclassified as deviceTime.
        case("parse name with colon and slash") {
            assertEquals(CLIResponse.Name("Repeater: East / West"), parse("> Repeater: East / West", "get name"))
        },
        // A clock reply is only recognized when "clock" is the pending query.
        case("parse device time requires the clock query") {
            assertEquals(CLIResponse.Raw("06:40 - 18/4/2025 UTC"), parse("06:40 - 18/4/2025 UTC"))
        },
    )

    private fun edgeCases() = listOf(
        // Content that starts with ">" but not "> " is not stripped.
        case("parse greater than in content not stripped") { assertEquals(CLIResponse.Name(">nosuchcommand"), parse(">nosuchcommand", "get name")) },
        // Only the first "> " is stripped.
        case("parse multiple greater than only first stripped") { assertEquals(CLIResponse.Name("> nested prompt"), parse("> > nested prompt", "get name")) },
        // "> " trims to ">", the bare prompt character, treated as empty content.
        case("parse empty after strip") { assertEquals(CLIResponse.Raw(""), parse("> ")) },
        case("parse just prompt") { assertEquals(CLIResponse.Raw(""), parse(">")) },
        // MARK: - Query Hint Matching (Integration): the flow RepeaterSettingsViewModel.handleCLIResponse uses.
        case("query hint matching longitude with prompt prefix") {
            var trimmedText = RemoteSwiftText.trimWhitespacesAndNewlines("> -120.338211")
            if (trimmedText.startsWith("> ")) trimmedText = trimmedText.drop(2)
            val isValidDouble = RemoteSwiftText.double(trimmedText) != null && !trimmedText.contains(",")
            assertTrue(isValidDouble)
            assertLongitude(parse("> -120.338211", "get lon"), -120.338211)
        },
        case("query hint matching repeat mode with prompt prefix") {
            var trimmedText = RemoteSwiftText.trimWhitespacesAndNewlines("> on")
            if (trimmedText.startsWith("> ")) trimmedText = trimmedText.drop(2)
            assertTrue(trimmedText.lowercase() == "on" || trimmedText.lowercase() == "off")
            assertEquals(CLIResponse.RepeatMode(true), parse("> on", "get repeat"))
        },
    )
}
