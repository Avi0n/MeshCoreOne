package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionDeviceSettingsPersistTest {
    @Test
    fun persistFaultRecordsTheIosErrorAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()

        persistDeviceSettingsAfterRadioAck(
            logger = connectionManagerLogger(this, recorded),
            failLinePrefix = FAILED_PERSIST_PATH_HASH_MODE,
        ) { error("db locked") }

        val line = recorded.single { it.message.contains(FAILED_PERSIST_PATH_HASH_MODE) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("ConnectionManager", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun persistSuccessDoesNotLog() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        var wrote = false

        persistDeviceSettingsAfterRadioAck(
            logger = connectionManagerLogger(this, recorded),
            failLinePrefix = FAILED_PERSIST_AUTO_ADD_CONFIG,
        ) { wrote = true }

        assertTrue(wrote)
        assertTrue(recorded.none { it.message.contains("Failed to persist") })
    }

    @Test
    fun cancellationIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("teardown")
        val thrown = assertFailsWith<CancellationException> {
            persistDeviceSettingsAfterRadioAck(
                logger = null,
                failLinePrefix = FAILED_PERSIST_CLIENT_REPEAT_STATE,
            ) { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aPersistErrorIsLoggedAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        persistDeviceSettingsAfterRadioAck(
            logger = connectionManagerLogger(this, recorded),
            failLinePrefix = FAILED_PERSIST_OTHER_PARAMS,
        ) { throw DeviceSettingsPersistProbeError() }
        val line = recorded.single { it.message.contains(FAILED_PERSIST_OTHER_PARAMS) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("probe"))
    }

    @Test
    fun knownRegionPrefixesMatchIos() {
        assertEquals("Failed to add known region", FAILED_ADD_KNOWN_REGION)
        assertEquals("Failed to remove known region", FAILED_REMOVE_KNOWN_REGION)
        assertEquals(
            "Failed to clear default flood scope after region removal",
            FAILED_CLEAR_DEFAULT_FLOOD_AFTER_REGION_REMOVAL,
        )
        assertEquals("Failed to persist default flood scope", FAILED_PERSIST_DEFAULT_FLOOD_SCOPE)
        assertEquals("Failed to persist pre-repeat settings", FAILED_PERSIST_PRE_REPEAT_SETTINGS)
        assertEquals(
            "Failed to persist cleared pre-repeat settings",
            FAILED_PERSIST_CLEARED_PRE_REPEAT_SETTINGS,
        )
        assertEquals("Failed to sync device time", FAILED_SYNC_DEVICE_TIME)
        assertEquals("Synced device time (was off by 12s)", syncedDeviceTimeLine(12))
        assertEquals("Device time in sync (drift: 2s)", deviceTimeInSyncLine(2))
        assertEquals("Failed to persist radio params", FAILED_PERSIST_RADIO_PARAMS)
        assertEquals("Failed to persist other params", FAILED_PERSIST_OTHER_PARAMS)
        assertEquals("Failed to persist node name", FAILED_PERSIST_NODE_NAME)
        assertEquals("Failed to persist BLE PIN", FAILED_PERSIST_BLE_PIN)
        assertEquals("Failed to persist device coordinates", FAILED_PERSIST_DEVICE_COORDINATES)
        assertEquals("Failed to persist device settings refresh", FAILED_PERSIST_DEVICE_SETTINGS_REFRESH)
        assertEquals("Failed to persist WiFi endpoint", FAILED_PERSIST_WIFI_ENDPOINT)
        assertTrue(failedToSyncDeviceTimeLine(IllegalStateException("timeout")).contains("timeout"))
    }
}

private class DeviceSettingsPersistProbeError : Error("probe")
