package one.meshcore.shared.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugLogExportTest {
    @Test
    fun emptyEntriesStillProducesHeader() {
        val text = DebugLogExport.format(emptyList(), connection = null)
        assertTrue(text.isNotBlank())
        assertContains(text, "=== MeshCore One Debug Export ===")
        assertContains(text, "=== Logs (Last 7 Days) ===")
        assertContains(text, "(No logs found)")
    }

    @Test
    fun aLongNonPasswordLineSurvivesExport() {
        val message = "connectionState=READY intent=wantsConnection " +
            "lastDisconnect=1000.0 source=disconnect reason=linkLoss"
        assertTrue(message.length > 40)
        val entry = DebugLogEntryDto.of(
            id = Uuid.normalized("E0000000-0000-0000-0000-000000000001"),
            timestamp = 1L,
            level = DebugLogLevel.INFO,
            source = DebugLogSource("test", "Connection"),
            message = message,
        )
        val text = DebugLogExport.format(listOf(entry), connection = null)
        assertContains(text, message)
        assertFalse(text.contains("..."))
    }

    @Test
    fun anAlreadyRedactedCliLineIsNotTruncated() {
        val message =
            "→ repeater CLI to=aabbccddeeff cmd=\"password ${LogRedaction.PASSWORD_PLACEHOLDER}\""
        assertTrue(message.length > 40)
        val entry = DebugLogEntryDto.of(
            id = Uuid.normalized("E0000000-0000-0000-0000-000000000003"),
            timestamp = 3L,
            level = DebugLogLevel.INFO,
            source = DebugLogSource("test", "CLI"),
            message = message,
        )
        val text = DebugLogExport.format(listOf(entry), connection = null)
        assertContains(text, message)
    }

    @Test
    fun keepsNonSensitiveMessages() {
        val entry = DebugLogEntryDto.of(
            id = Uuid.normalized("E0000000-0000-0000-0000-000000000002"),
            timestamp = 2L,
            level = DebugLogLevel.INFO,
            source = DebugLogSource("test", "App"),
            message = "hello",
        )
        val text = DebugLogExport.format(listOf(entry), connection = null)
        assertContains(text, "hello")
        assertContains(text, "1970-01-01 00:00:00.002 [INFO] App: hello")
        assertContains(text, "Total entries: 1")
    }

    @Test
    fun utcLogTimestampMatchesKnownUnixVector() {
        assertEquals("2001-09-09 01:46:40.000", formatUtcLogTimestamp(1_000_000_000_000L))
        assertEquals("1970-01-01 00:00:00.000", formatUtcLogTimestamp(0L))
        assertEquals("2001-09-09T01:46:40Z", formatUtcIso8601(1_000_000_000L))
        assertEquals("1970-01-01T00:16:40Z", formatUtcIso8601(1_000L))
    }

    @Test
    fun platformHeaderCarriesBuildAndHost() {
        val text = DebugLogExport.format(
            entries = emptyList(),
            connection = null,
            platform = PlatformDiagnostics(
                appVersion = "1.4.0",
                buildNumber = "42",
                deviceModel = "Pixel 9",
                osName = "Android",
                osVersion = "16",
                exportedAtEpochMillis = 1_000_000_000_000L,
            ),
        )
        assertContains(text, "=== MeshCore One Debug Export ===")
        assertContains(text, "Exported: 2001-09-09T01:46:40Z")
        assertContains(text, "App Version: 1.4.0 (42)")
        assertContains(text, "Device: Pixel 9, Android 16")
    }

    @Test
    fun theConnectionSectionOpensTheExport() {
        val text = DebugLogExport.format(
            entries = emptyList(),
            connection = ConnectionDiagnostics(
                state = "disconnected",
                intent = "wantsConnection",
                lastDisconnectDiagnostic = "1000.0 source=disconnect, reason=userInitiated",
                deviceName = "MeshCore-F19",
                deviceId = "A0000000-0000-0000-0000-000000000042",
                lastConnectedEpochSeconds = 1_000.0,
            ),
        )
        assertContains(text, "=== Connection ===")
        assertContains(text, "State: disconnected")
        assertContains(text, "Intent: wantsConnection")
        assertContains(text, "Last Disconnect Diagnostic: 1000.0 source=disconnect, reason=userInitiated")
        assertContains(text, "MeshCore-F19")
        // The device row abbreviates the row id, as the iOS export does; the full uuid never ships.
        assertContains(text, "A0000000")
        assertFalse(text.contains("A0000000-0000-0000-0000-000000000042"))
        assertContains(text, "Last Connected: 1970-01-01T00:16:40Z")
    }

    @Test
    fun aMissingDisconnectDiagnosticSaysSoRatherThanGoingQuiet() {
        val text = DebugLogExport.format(
            entries = emptyList(),
            connection = ConnectionDiagnostics(
                state = "ready",
                intent = "wantsConnection",
                lastDisconnectDiagnostic = null,
                deviceName = null,
                deviceId = null,
                lastConnectedEpochSeconds = null,
            ),
        )
        assertContains(text, "Last Disconnect Diagnostic: Unavailable")
        assertFalse(text.contains("Device:"))
    }

    @Test
    fun theDeviceAndBatterySectionsFollowTheConnection() {
        val text = DebugLogExport.format(
            entries = emptyList(),
            connection = ConnectionDiagnostics(
                state = "ready",
                intent = "wantsConnection",
                lastDisconnectDiagnostic = "ok",
                deviceName = "MeshCore-F19",
                deviceId = "A0000000-0000-0000-0000-000000000042",
                lastConnectedEpochSeconds = 1_000.0,
                bleSummary = "BLE: transport=bluetooth, state=READY, sessionPresent=true",
            ),
            device = DeviceDiagnostics(
                isConnected = true,
                name = "MeshCore-F19",
                firmwareVersionString = "1.9.0",
                firmwareVersion = 19,
                manufacturerName = "Heltec",
                buildDate = "2026-01-01",
                frequencyKHz = 869_618,
                bandwidthHz = 62_500,
                spreadingFactor = 11,
                codingRate = 5,
                txPower = 22,
                maxTxPower = 22,
                maxContacts = 350,
                maxChannels = 40,
                manualAddContacts = false,
                multiAcks = 0,
            ),
            battery = BatteryDiagnostics(percentage = 84, voltage = 4.05, millivolts = 4050),
        )
        assertContains(text, "=== Device Info ===")
        assertContains(text, "Firmware: 1.9.0 (v19)")
        assertContains(text, "Radio: 869.618 MHz, BW 62500 kHz, SF11, CR5")
        assertContains(text, "=== Battery ===")
        assertContains(text, "Level: 84%")
        assertContains(text, "Voltage: 4.05 V")
        assertContains(text, "Raw: 4050 mV")
        assertContains(text, "BLE: transport=bluetooth")
        assertContains(text, "=== Logs (Last 7 Days) ===")
        assertContains(text, "(No logs found)")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun exportFlushesThenDropsRowsOutsideTheRetentionWindow() = runTest {
        val now = DebugLogRetention.WINDOW_MS + 5_000L
        val store = InMemoryDebugLogStore()
        val buffer = DebugLogBuffer(store, backgroundScope, now = { now })
        store.saveDebugLogEntries(
            listOf(
                logEntry(idSuffix = "000000000011", timestamp = 1L, message = "stale"),
                logEntry(idSuffix = "000000000012", timestamp = now - 1_000L, message = "kept"),
            ),
        )
        buffer.append(logEntry(idSuffix = "000000000013", timestamp = now, message = "buffered"))

        val text = exportRetainedDebugLog(
            buffer = buffer,
            store = store,
            nowMillis = now,
        ) { entries ->
            DebugLogExport.format(entries, connection = null)
        }

        assertContains(text, "kept")
        assertContains(text, "buffered")
        assertFalse(text.contains("stale"))
        assertEquals(2, store.countDebugLogEntries())
    }

    private fun logEntry(idSuffix: String, timestamp: Long, message: String) = DebugLogEntryDto.of(
        id = Uuid.normalized("E0000000-0000-0000-0000-$idSuffix"),
        timestamp = timestamp,
        level = DebugLogLevel.INFO,
        source = DebugLogSource("test", "App"),
        message = message,
    )
}
