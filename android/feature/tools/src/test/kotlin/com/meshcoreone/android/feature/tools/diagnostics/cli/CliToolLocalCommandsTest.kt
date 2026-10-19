// PortedFrom: MC1Tests/Views/Tools/CLI/CLIToolViewModelLocalCommandsTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.tools.diagnostics.support.OriginalCase
import com.meshcoreone.android.feature.tools.diagnostics.support.TestScheduler
import com.meshcoreone.android.feature.tools.diagnostics.support.XmlDiagnosticsText
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

private const val ADAPTED = "adapted-service-fake"

class CliToolLocalCommandsTest {
    private val scheduler = TestScheduler()
    private val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)
    private val floods = mutableListOf<Boolean>()

    private fun makeHolder(settings: FakeSettings, device: DeviceDTO? = null): CliToolStateHolder = holder.also {
        it.configure(
            dependencies(
                settingsService = { settings }, radioId = { TEST_RADIO }, connectedDevice = { device },
                sendSelfAdvert = { flood -> floods += flood },
            ),
            localDeviceName = "TestDevice",
        )
        it.setActiveSession(CliSession.local("TestDevice"))
        scheduler.runCurrent()
    }

    private fun run(line: String) {
        holder.executeCommand(line)
        scheduler.runCurrent()
    }

    private fun lastResponse(): String? = holder.state.value.terminal.outputLines.lastOrNull { it.type == CliOutputType.RESPONSE }?.text

    private fun lastLine(): CliOutputLine = holder.state.value.terminal.outputLines.last()

    private val pending get() = holder.state.value.pendingConfirmation

    // MARK: - Reads

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::clock prints the device time in UTC()", ADAPTED)
    fun `clock prints the device time in UTC`() {
        makeHolder(FakeSettings(deviceTime = Instant.ofEpochSecond(1_700_000_000)))
        run("clock")
        assertEquals("22:13 - 14/11/2023 UTC", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::clock sync sets the device time and prints OK()", ADAPTED)
    fun `clock sync sets the device time and prints OK`() {
        val settings = FakeSettings()
        makeHolder(settings)
        run("clock sync")
        assertEquals(1, settings.setTimeCalls.size)
        assertTrue(lastResponse().orEmpty().startsWith("OK - clock set:"))
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get name prints the device name()", ADAPTED)
    fun `get name prints the device name`() {
        makeHolder(FakeSettings(name = "FieldNode"))
        run("get name")
        assertEquals("> FieldNode", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get bat prints millivolts()", ADAPTED)
    fun `get bat prints millivolts`() {
        makeHolder(FakeSettings(batteryMillivolts = 3921))
        run("get bat")
        assertEquals("> 3921 mV", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get radio prints comma-separated params()", ADAPTED)
    fun `get radio prints comma-separated params`() {
        makeHolder(FakeSettings(radioFrequency = 869.525, radioBandwidth = 250.0, radioSpreadingFactor = 11u, radioCodingRate = 5u))
        run("get radio")
        assertEquals("> 869.525,250,11,5", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get public key prints uppercase hex()", ADAPTED)
    fun `get public key prints uppercase hex`() {
        makeHolder(FakeSettings(publicKey = Bytes.of(0xAB, 0x01, 0xFF)))
        run("get public.key")
        assertEquals("> AB01FF", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get lat prints the latitude at six-decimal precision()", ADAPTED)
    fun `get lat prints the latitude at six-decimal precision`() {
        makeHolder(FakeSettings(latitude = 37.774929))
        run("get lat")
        assertEquals("> 37.774929", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get lon prints the longitude trimming trailing zeros()", ADAPTED)
    fun `get lon prints the longitude trimming trailing zeros`() {
        makeHolder(FakeSettings(longitude = -122.41941))
        run("get lon")
        assertEquals("> -122.41941", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::ver prints version and build()", ADAPTED)
    fun `ver prints version and build`() {
        makeHolder(FakeSettings(version = "1.13.0", firmwareBuild = "deadbeef"))
        run("ver")
        assertEquals("1.13.0 (Build: deadbeef)", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::board prints the model()", ADAPTED)
    fun `board prints the model`() {
        makeHolder(FakeSettings(model = "Heltec V3"))
        run("board")
        assertEquals("Heltec V3", lastResponse())
    }

    // MARK: - Writes

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set tx round-trips and prints OK()", ADAPTED)
    fun `set tx round-trips and prints OK`() {
        val settings = FakeSettings(txPower = 5)
        makeHolder(settings)
        run("set tx 22")
        assertEquals(listOf<Byte>(22), settings.setTxPowerCalls)
        assertTrue(settings.setCustomVarCalls.isEmpty())
        assertEquals("OK", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set radio converts units and prints OK()", ADAPTED)
    fun `set radio converts units and prints OK`() {
        val settings = FakeSettings()
        makeHolder(settings)
        run("set radio 869.525,250,11,5")
        run("y")
        assertEquals(FakeSettings.RadioCall(869_525u, 250_000u, 11u, 5u), settings.setRadioCalls.first())
        assertEquals("OK", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set freq changes frequency and keeps other radio params()", ADAPTED)
    fun `set freq changes frequency and keeps other radio params`() {
        val settings = FakeSettings(radioFrequency = 869.525, radioBandwidth = 250.0, radioSpreadingFactor = 11u, radioCodingRate = 5u)
        makeHolder(settings)
        run("set freq 869.9")
        run("y")
        assertEquals(FakeSettings.RadioCall(869_900u, 250_000u, 11u, 5u), settings.setRadioCalls.first())
        assertEquals("OK", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set lat combines with the seeded longitude()", ADAPTED)
    fun `set lat combines with the seeded longitude`() {
        val settings = FakeSettings(latitude = 0.0, longitude = -122.4194)
        makeHolder(settings)
        run("set lat 37.7749")
        assertEquals(37.7749 to -122.4194, settings.setCoordinatesCalls.first())
        assertEquals("OK", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set lon combines with the seeded latitude()", ADAPTED)
    fun `set lon combines with the seeded latitude`() {
        val settings = FakeSettings(latitude = 37.7749, longitude = 0.0)
        makeHolder(settings)
        run("set lon -122.4194")
        assertEquals(37.7749 to -122.4194, settings.setCoordinatesCalls.first())
        assertEquals("OK", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set multi acks uses the connected device as base()", ADAPTED)
    fun `set multi acks uses the connected device as base`() {
        val settings = FakeSettings(manualAddContacts = true)
        makeHolder(settings, device(manualAddContacts = true, multiAcks = 0u))
        run("set multi.acks 1")
        assertEquals(listOf<UByte>(1u), settings.setOtherParamsCalls.map { it.second })
        assertEquals("OK", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set path hash mode prints OK()", ADAPTED)
    fun `set path hash mode prints OK`() {
        val settings = FakeSettings(pathHashMode = 0u)
        makeHolder(settings)
        run("set path.hash.mode 2")
        assertEquals(listOf<UByte>(2u), settings.setPathHashModeCalls)
        assertEquals("OK", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set name refreshes the prompt name()", ADAPTED)
    fun `set name refreshes the prompt name`() {
        val settings = FakeSettings(name = "Old")
        makeHolder(settings)
        run("set name New Name")
        assertEquals(listOf("New Name"), settings.setNameCalls)
        assertEquals("New Name", holder.state.value.localDeviceName)
        assertTrue(holder.promptText.startsWith("New Name"))
    }

    // MARK: - Confirmation flow

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::reboot requires confirmation and executes on yes()", ADAPTED)
    fun `reboot requires confirmation and executes on yes`() {
        val settings = FakeSettings()
        makeHolder(settings)
        run("reboot")
        assertEquals(CliLocalCommand.Reboot, pending)
        assertTrue(holder.promptText.contains("confirm reboot?"), holder.promptText)
        run("y")
        assertTrue(settings.rebootCalled)
        assertEquals("OK - rebooting", lastResponse())
        assertNull(pending)
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::reboot is cancelled on no()", ADAPTED)
    fun `reboot is cancelled on no`() {
        val settings = FakeSettings()
        makeHolder(settings)
        run("reboot")
        run("no")
        assertFalse(settings.rebootCalled)
        assertNull(pending)
        assertTrue(holder.state.value.terminal.outputLines.any { "cancelled" in it.text })
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::confirmation answer is not added to history()", ADAPTED)
    fun `confirmation answer is not added to history`() {
        makeHolder(FakeSettings())
        run("reboot")
        run("y")
        assertTrue("reboot" in holder.state.value.terminal.commandHistory)
        assertFalse("y" in holder.state.value.terminal.commandHistory)
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::cancel while pending clears the confirmation()", ADAPTED)
    fun `cancel while pending clears the confirmation`() {
        val settings = FakeSettings()
        makeHolder(settings)
        run("reboot")
        assertEquals(CliLocalCommand.Reboot, pending)
        holder.cancelCurrentCommand()
        assertNull(pending)
        assertFalse(settings.rebootCalled)
        assertTrue(holder.state.value.terminal.outputLines.any { "cancelled" in it.text })
    }

    // MARK: - Advert closure

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::advert sends zero-hop()", ADAPTED)
    fun `advert sends zero-hop`() {
        makeHolder(FakeSettings())
        run("advert")
        assertEquals(listOf(false), floods)
        assertEquals("OK - zero-hop advert sent", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::floodadv sends flood()", ADAPTED)
    fun `floodadv sends flood`() {
        makeHolder(FakeSettings())
        run("floodadv")
        assertEquals(listOf(true), floods)
        assertEquals("OK - flood advert sent", lastResponse())
    }

    // MARK: - Errors

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::verification failure renders an error line()", ADAPTED)
    fun `verification failure renders an error line`() {
        val settings = FakeSettings(txPower = 5).apply { suppressWrites = true }
        makeHolder(settings)
        run("set tx 22")
        assertEquals(CliOutputType.ERROR, lastLine().type)
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::set radio bad arguments prints usage()", ADAPTED)
    fun `set radio bad arguments prints usage`() {
        makeHolder(FakeSettings())
        run("set radio 869.525")
        assertEquals(CliOutputType.ERROR, lastLine().type)
        assertTrue(lastLine().text.contains("set radio"), lastLine().text)
    }

    // MARK: - Custom variables

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get custom prints a sorted var dictionary()", ADAPTED)
    fun `get custom prints a sorted var dictionary`() {
        makeHolder(FakeSettings(customVars = mapOf("gps_interval" to "60", "gps" to "0")))
        run("get custom")
        assertEquals("2 vars\ngps=0\ngps_interval=60", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get custom with no vars prints the empty string()", ADAPTED)
    fun `get custom with no vars prints the empty string`() {
        makeHolder(FakeSettings())
        run("get custom")
        assertEquals("no custom var", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::bare get of a custom var prints the value or reports it unknown()", ADAPTED)
    fun `bare get of a custom var prints the value or reports it unknown`() {
        makeHolder(FakeSettings(customVars = mapOf("gps" to "1")))
        run("get gps")
        assertEquals("> 1", lastResponse())
        run("get nope")
        assertEquals("Unknown var nope", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::a stripped-key miss reports the stripped name()", ADAPTED)
    fun `a stripped-key miss reports the stripped name`() {
        makeHolder(FakeSettings())
        run("get _nope")
        assertEquals("Unknown var nope", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::wifi_ssid round-trips through a bare set then get()", ADAPTED)
    fun `wifi_ssid round-trips through a bare set then get`() {
        val settings = FakeSettings()
        makeHolder(settings)
        run("set wifi_ssid MyNetwork")
        assertEquals(listOf("wifi_ssid" to "MyNetwork"), settings.setCustomVarCalls)
        assertEquals("Var wifi_ssid set to MyNetwork", lastResponse())
        assertNull(pending)
        run("get wifi_ssid")
        assertEquals("> MyNetwork", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::a set custom var mapping an illegal-argument device error prints the parity string()", ADAPTED)
    fun `a set custom var mapping an illegal-argument device error prints the parity string`() {
        makeHolder(FakeSettings().apply { nextSetCustomVarErrorCode = 6u })
        run("set bogus 1")
        assertEquals("can't find custom var", lastResponse())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::a set custom var surfaces a non-illegal-argument device error via the error path()", ADAPTED)
    fun `a set custom var surfaces a non-illegal-argument device error via the error path`() {
        makeHolder(FakeSettings().apply { nextSetCustomVarErrorCode = 1u })
        run("set gps 1")
        assertEquals(CliOutputType.ERROR, lastLine().type)
        assertNotEquals("can't find custom var", lastLine().text)
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::old firmware that cannot enumerate vars errors instead of faking a miss()", ADAPTED)
    fun `old firmware that cannot enumerate vars errors instead of faking a miss`() {
        makeHolder(FakeSettings().apply { nextGetCustomVarsErrorCode = 1u })
        run("get nope")
        assertEquals(CliOutputType.ERROR, lastLine().type)
        assertNotEquals("Unknown var nope", lastLine().text)
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::a successful custom-var fetch feeds completion keys()", ADAPTED)
    fun `a successful custom-var fetch feeds completion keys`() {
        makeHolder(FakeSettings(customVars = mapOf("gps" to "0", "gps_interval" to "60")))
        run("get custom")
        assertEquals(setOf("gps", "gps_interval"), holder.completionEngine.customVarKeys.toSet())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::the connection prefetch populates completion keys()", ADAPTED)
    fun `the connection prefetch populates completion keys`() {
        makeHolder(FakeSettings(customVars = mapOf("gps" to "0", "wifi_ssid" to "off")))
        scheduler.run { holder.updateCustomVarKeysForCompletion() }
        assertEquals(setOf("gps", "wifi_ssid"), holder.completionEngine.customVarKeys.toSet())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::disconnect flushes learned custom-var completion keys()", ADAPTED)
    fun `disconnect flushes learned custom-var completion keys`() {
        val admin = FakeRepeaterAdmin()
        val settings = FakeSettings(customVars = mapOf("gps" to "0", "wifi_ssid" to "off"))
        holder.configure(
            dependencies(repeaterAdminService = { admin }, settingsService = { settings }, radioId = { TEST_RADIO }),
            localDeviceName = "TestDevice",
        )
        holder.setActiveSession(CliSession.local("TestDevice"))
        scheduler.run { holder.updateCustomVarKeysForCompletion() }
        assertTrue(holder.completionEngine.customVarKeys.isNotEmpty())

        holder.configure(dependencies(), localDeviceName = "TestDevice")
        assertTrue(holder.completionEngine.customVarKeys.isEmpty())
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::get custom on a remote session does not touch the settings service()", ADAPTED)
    fun `get custom on a remote session does not touch the settings service`() {
        val settings = FakeSettings(customVars = mapOf("gps" to "0"))
        makeHolder(settings)
        holder.setActiveSession(CliSession.remote(EntityKey(TEST_RADIO, UUID.randomUUID()), "Repeater", 1u))
        run("get custom")
        assertEquals(0, settings.readCount)
    }

    // MARK: - Disconnected

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::commands report not connected when disconnected(line : String)", ADAPTED)
    fun `commands report not connected when disconnected`() {
        listOf("clock", "ver", "board", "get name", "set tx 5", "reboot", "get custom", "get gps", "set wifi_ssid x").forEach { line ->
            val scheduler = TestScheduler()
            val holder = CliToolStateHolder(scheduler.scope, XmlDiagnosticsText(), scheduler.clock, FAKE_ERRORS)
            holder.configure(dependencies(), localDeviceName = "TestDevice")
            holder.setActiveSession(CliSession.local("TestDevice"))
            holder.executeCommand(line)
            scheduler.runCurrent()
            if (holder.state.value.pendingConfirmation != null) {
                holder.executeCommand("y")
                scheduler.runCurrent()
            }
            assertEquals(CliOutputType.ERROR, holder.state.value.terminal.outputLines.last().type, "line [$line]")
        }
    }

    // MARK: - Remote passthrough regression

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::local get on a remote session does not touch the settings service()", ADAPTED)
    fun `local get on a remote session does not touch the settings service`() {
        val settings = FakeSettings(name = "FieldNode")
        makeHolder(settings)
        holder.setActiveSession(CliSession.remote(EntityKey(TEST_RADIO, UUID.randomUUID()), "Repeater", 1u))
        run("get name")
        assertEquals(0, settings.readCount)
        assertFalse(holder.state.value.terminal.outputLines.any { it.text == "> FieldNode" })
    }

    @Test @OriginalCase("CLIToolViewModelLocalCommandsTests::ghost text is suppressed while a confirmation is pending()", ADAPTED)
    fun `ghost text is suppressed while a confirmation is pending`() {
        makeHolder(FakeSettings())
        run("reboot")
        assertEquals(CliLocalCommand.Reboot, pending)
        holder.updateInput("y")
        holder.updateGhostText(cursorAtEnd = true)
        assertEquals("", holder.state.value.terminal.ghostText)
    }

    // Native WP-316 boundary cases.

    @Test
    fun `clock formats UTC components with Swift field widths`() {
        assertEquals("00:00 - 1/1/1970 UTC", LocalCommandOutput.clock(Instant.EPOCH))
        assertEquals("22:14 - 14/11/2023 UTC", LocalCommandOutput.clock(Instant.ofEpochMilli(1_700_000_059_999)))
        assertEquals("23:59 - 31/12/1969 UTC", LocalCommandOutput.clock(Instant.ofEpochSecond(-1)))
        assertEquals("00:00 - 29/2/2000 UTC", LocalCommandOutput.clock(Instant.ofEpochSecond(951_782_400)))
    }

    @Test
    fun `decimal and coordinate output match printf rounding from the Swift oracle`() {
        val decimals = mapOf(
            869.525 to "869.525", 250.0 to "250", 0.125 to "0.125", 1.0005 to "1", 2.0005 to "2.001",
            0.0005 to "0.001", -0.0004 to "-0", 7.8125 to "7.812", 1e-7 to "0", 1234567.8915 to "1234567.891", -0.0 to "-0",
        )
        decimals.forEach { (value, expected) -> assertEquals(expected, LocalCommandOutput.decimal(value), "decimal($value)") }
        val coordinates = mapOf(
            37.774929 to "37.774929", -122.41941 to "-122.41941", -0.0000004 to "-0", 45.0000005 to "45",
            12.3456785 to "12.345678", 1.0000015 to "1.000001", -33.8688197 to "-33.86882",
        )
        coordinates.forEach { (value, expected) -> assertEquals(expected, LocalCommandOutput.coordinate(value), "coordinate($value)") }
        assertEquals(7_813u, LocalCommandOutput.bandwidthHz(7.8125))
        assertEquals(433_001u, LocalCommandOutput.freqKHz(433.0005))
    }

    @Test
    fun `a cancelled local command writes no output after the cancel line`() {
        val settings = FakeSettings()
        makeHolder(settings)
        holder.executeCommand("ver")
        holder.cancelCurrentCommand()
        scheduler.runCurrent()
        val last = lastLine()
        assertEquals(CliOutputType.ERROR, last.type)
        assertTrue("cancelled" in last.text)
        assertFalse(holder.state.value.isWaitingForResponse)
    }
}
