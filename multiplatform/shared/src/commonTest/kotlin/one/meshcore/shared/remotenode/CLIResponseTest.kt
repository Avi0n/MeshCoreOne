package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The content classification [CLIResponse.parse] performs, which drives content-based CLI matching. */
class CLIResponseTest {
    @Test
    fun okAndErrorClassification() {
        assertIs<CLIResponse.Ok>(CLIResponse.parse("OK"))
        assertIs<CLIResponse.Ok>(CLIResponse.parse("OK - clock set"))
        assertIs<CLIResponse.Error>(CLIResponse.parse("Error: something failed"))
        assertIs<CLIResponse.UnknownCommand>(CLIResponse.parse("Error: unknown command 'foo'"))
    }

    @Test
    fun promptPrefixIsStripped() {
        assertIs<CLIResponse.Ok>(CLIResponse.parse("> OK"))
        val raw = CLIResponse.parse(">")
        assertIs<CLIResponse.Raw>(raw)
        assertEquals("", raw.text)
    }

    @Test
    fun versionByContentOrQueryHint() {
        assertIs<CLIResponse.Version>(CLIResponse.parse("MeshCore v1.11.0 (2025-04-18)"))
        assertIs<CLIResponse.Version>(CLIResponse.parse("v1.11.0 (2025-04-18)"))
        assertIs<CLIResponse.Version>(CLIResponse.parse("custom-build", query = "ver"))
    }

    @Test
    fun freeformFieldsPreferQueryHintOverDeviceTimeHeuristic() {
        // A name containing ":" and "/" would otherwise be misread as a clock response.
        val name = CLIResponse.parse("KD7ABC / 145.230", query = "get name")
        assertIs<CLIResponse.Name>(name)
        assertEquals("KD7ABC / 145.230", name.text)

        assertIs<CLIResponse.OwnerInfo>(CLIResponse.parse("owner blurb", query = "get owner.info"))
        assertIs<CLIResponse.DeviceTime>(CLIResponse.parse("06:40 - 18/4/2025 UTC", query = "clock"))
    }

    @Test
    fun deviceTimeShapeIsGatedOnTheClockQuery() {
        // The ":" + "/" shape also appears in owner info, names and the firmware password echo, so only a
        // reply to `clock` is a device time. Without the gate a password echo parses as a clock reading and
        // an accepted change reports as failed.
        assertIs<CLIResponse.Raw>(CLIResponse.parse("Contact: KD7ABC / 145.230"))
        assertIs<CLIResponse.Raw>(CLIResponse.parse("password now: a/b:c"))
        assertIs<CLIResponse.DeviceTime>(CLIResponse.parse("06:40 - 18/4/2025 UTC", query = "clock"))
    }

    @Test
    fun aClockReplyIsNotAPlausibleAnswerToACoordinateQuery() {
        // Firmware that does not echo the wire prefix falls back to this shape check; a stale clock reply
        // must not be handed to "get lat" as its answer.
        assertEquals(false, CLIResponse.isPlausibleResponse("06:40 - 18/4/2025 UTC", "get lat"))
        assertEquals(true, CLIResponse.isPlausibleResponse("06:40 - 18/4/2025 UTC", "clock"))
    }

    @Test
    fun radioParsesFourCommaFields() {
        val radio = CLIResponse.parse("915.000,250.0,10,5", query = "get radio")
        assertIs<CLIResponse.Radio>(radio)
        assertEquals(915.0, radio.frequency)
        assertEquals(250.0, radio.bandwidth)
        assertEquals(10, radio.spreadingFactor)
        assertEquals(5, radio.codingRate)
    }

    @Test
    fun numericAndBooleanFieldsByQueryHint() {
        assertEquals(20, (CLIResponse.parse("20", query = "get tx") as CLIResponse.TxPower).value)
        assertEquals(true, (CLIResponse.parse("on", query = "get repeat") as CLIResponse.RepeatMode).enabled)
        assertEquals(false, (CLIResponse.parse("off", query = "get repeat") as CLIResponse.RepeatMode).enabled)
        val advert = CLIResponse.parse("120", query = "get advert.interval") as CLIResponse.AdvertInterval
        assertEquals(120, advert.minutes)
        val flood = CLIResponse.parse("24", query = "get flood.advert.interval") as CLIResponse.FloodAdvertInterval
        assertEquals(24, flood.hours)
        assertEquals(8, (CLIResponse.parse("8", query = "get flood.max") as CLIResponse.FloodMax).hops)
        assertEquals(47.5, (CLIResponse.parse("47.5", query = "get lat") as CLIResponse.Latitude).value)
        assertEquals(-122.3, (CLIResponse.parse("-122.3", query = "get lon") as CLIResponse.Longitude).value)
    }

    @Test
    fun txPowerParsesZephCoreAdaptivePowerControlReplies() {
        // Stock firmware: bare integer, including negative.
        assertEquals(22, (CLIResponse.parse("> 22", query = "get tx") as CLIResponse.TxPower).value)
        assertEquals(-5, (CLIResponse.parse("-5", query = "get tx") as CLIResponse.TxPower).value)
        // ZephCore apc=off: leading integer with a dBm suffix.
        assertEquals(22, (CLIResponse.parse("> 22dBm (apc=off)", query = "get tx") as CLIResponse.TxPower).value)
        // ZephCore apc=on: the configured ceiling (max=) wins over the reduced live power.
        val apcOn = CLIResponse.parse("> 16dBm (apc=on max=22 reduction=6 margin=18.5 target=16)", query = "get tx")
        assertEquals(22, (apcOn as CLIResponse.TxPower).value)
        // A "get radio" CSV misattributed to "get tx" must not read its leading frequency as a power.
        assertIs<CLIResponse.Raw>(CLIResponse.parse("910.0,250.0,10,5", query = "get tx"))
    }

    @Test
    fun unmatchedTextIsRaw() {
        val raw = CLIResponse.parse("some arbitrary text")
        assertIs<CLIResponse.Raw>(raw)
        assertEquals("some arbitrary text", raw.text)
    }
}
