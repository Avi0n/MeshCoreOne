// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/CLIResponseTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.remotenodes.cli

import com.meshcoreone.android.feature.remotenodes.cli.CLIResponse.Companion.isPlausibleResponse
import com.meshcoreone.android.feature.remotenodes.cli.CLIResponse.Companion.parse
import com.meshcoreone.android.feature.remotenodes.cli.CLIResponse.Companion.splitEchoedPrefix
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Swift suite "CLIResponse parsing" (MC1ServicesTests). */
@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class FeatureCLIResponseServicesVectorTest(private val label: String, private val body: () -> Unit) {
    @org.junit.Test
    fun originalCase() = body()

    companion object {
        @JvmStatic
        @org.junit.runners.Parameterized.Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = CLIResponseServicesTestVectors().sourceCases().map {
            arrayOf<Any>(it.first, it.second)
        }
    }
}

private fun sourceCase(name: String, body: () -> Unit): Pair<String, () -> Unit> = name to body

private class CLIResponseServicesTestVectors {
    private fun case(name: String, body: () -> Unit): Pair<String, () -> Unit> =
        sourceCase("CLIResponseTests::$name()", body)

    fun sourceCases(): List<Pair<String, () -> Unit>> = listOf(
        // MARK: - TX Power
        case("Bare integer parses as TX power") {
            assertEquals(CLIResponse.TxPower(22), parse("> 22", "get tx"))
            assertEquals(CLIResponse.TxPower(-5), parse("-5", "get tx"))
        },
        case("ZephCore adaptive power annotation reads the max ceiling") {
            assertEquals(CLIResponse.TxPower(22), parse("> 22dBm (apc=off)", "get tx"))
            assertEquals(
                CLIResponse.TxPower(22),
                parse("> 16dBm (apc=on max=22 reduction=6 margin=18.5 target=16)", "get tx"),
            )
        },
        case("Radio CSV never parses as TX power") {
            // A "get radio" reply misattributed to "get tx" once showed 910 dBm for a 910.525 MHz
            // repeater; the leading integer of a decimal or CSV must not match.
            assertEquals(CLIResponse.Raw("910.525,62.500,7,7"), parse("> 910.525,62.500,7,7", "get tx"))
            assertEquals(CLIResponse.Raw("910.5"), parse("910.5", "get tx"))
        },
        // MARK: - Radio
        case("Radio CSV parses only for the radio query") {
            assertEquals(CLIResponse.Radio(915.0, 250.0, 10, 5), parse("> 915.000,250.0,10,5", "get radio"))
            assertEquals(CLIResponse.Raw("22"), parse("> 22", "get radio"))
        },
        // MARK: - Device Time
        case("Clock reply parses as device time only for the clock query") {
            assertEquals(CLIResponse.DeviceTime("06:40 - 18/4/2025 UTC"), parse("06:40 - 18/4/2025 UTC", "clock"))
            // The ":" + "/" shape also appears in free-form text; without the clock query it stays raw.
            assertEquals(CLIResponse.Raw("Contact: KD7ABC / 145.230"), parse("Contact: KD7ABC / 145.230"))
            assertEquals(CLIResponse.Raw("06:40 - 18/4/2025 UTC"), parse("06:40 - 18/4/2025 UTC", "get radio"))
        },
        // MARK: - Response Matching
        case("Structured get queries reject replies of the wrong shape") {
            assertFalse(isPlausibleResponse("> 910.525,62.500,7,7", "get tx"))
            assertFalse(isPlausibleResponse("> 22", "get radio"))
            assertFalse(isPlausibleResponse("Alpha Repeater", "get lat"))
            assertFalse(isPlausibleResponse("> 22", "clock"))
        },
        case("Structured get queries accept their own shape and errors") {
            assertTrue(isPlausibleResponse("> 22", "get tx"))
            assertTrue(isPlausibleResponse("> 915.000,250.0,10,5", "get radio"))
            assertTrue(isPlausibleResponse("-36.8485", "get lat"))
            assertTrue(isPlausibleResponse("ERR: not allowed", "get tx"))
        },
        case("Free-form and action commands accept any reply") {
            // Firmware success replies are not uniformly "OK"-prefixed.
            assertTrue(isPlausibleResponse("password now: hunter2", "password hunter2"))
            assertTrue(isPlausibleResponse("OK", "set tx 22"))
            assertTrue(isPlausibleResponse("Alpha Repeater", "get name"))
            assertTrue(isPlausibleResponse("regions saved", "region save"))
        },
        case("Echoed wire prefix splits into prefix and body") {
            val split = splitEchoedPrefix("3A|> 22dBm (apc=off)")
            assertEquals("3A|", split?.prefix)
            assertEquals("> 22dBm (apc=off)", split?.body)
        },
        case("Multi-line body survives prefix splitting") {
            assertEquals("US/CA^\n  local F", splitEchoedPrefix("0F|US/CA^\n  local F")?.body)
        },
        case("Ordinary reply text is not mistaken for a wire prefix") {
            // Only two uppercase hex digits plus the separator qualify.
            assertNull(splitEchoedPrefix("> 22dBm (apc=off)"))
            assertNull(splitEchoedPrefix("3a|lowercase"))
            assertNull(splitEchoedPrefix("no|t hex"))
            assertNull(splitEchoedPrefix("FF|"))
            assertNull(splitEchoedPrefix(""))
        },
    )
}
