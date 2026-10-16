package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPresets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A radio preset write starts at [launchRadioPresetApply]. The first radio command fails with a
 * retryable connection error. The retry alert shows that text, [errorMessage] stays clear, and one
 * retry sends the radio command once more.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RadioPresetRetryFixtureTest {
    @Test
    fun aFailedPresetApplyRetriesOnceWithoutADuplicateRadioCommand() = runTest {
        val preset = RadioPresets.all.first()
        val alert = RetryAlertState()
        val errorMessage = mutableListOf<String?>()
        var radioCommands = 0
        var snapshot: RadioDeviceSnapshot? = null
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, _ -> },
        )

        launchRadioPresetApply(
            scope,
            RadioPresetWrite(
                preset = preset,
                repeatEnabled = false,
                inFlight = false,
                snapshot = ORIGINAL_SNAPSHOT,
                onApplyPreset = {
                    radioCommands += 1
                    if (radioCommands == 1) throw IllegalStateException(NOT_CONNECTED)
                },
                onApplyRepeatPreset = { error("unused") },
                otherParamsProvider = { null },
                onInFlight = {},
                onError = { errorMessage += it },
                onSnapshot = { snapshot = it },
                onOtherParams = {},
                settingsErrorText = { it.message ?: "settings failed" },
                retryAlert = alert,
                onMaxRetriesExceeded = {},
            ),
        )
        advanceUntilIdle()

        assertEquals(1, radioCommands)
        assertEquals(listOf<String?>(null), errorMessage)
        assertTrue(alert.isPresented)
        assertEquals(NOT_CONNECTED, alert.message)
        assertNull(snapshot?.appliedRadioPresetID)

        alert.retry()
        advanceUntilIdle()

        assertEquals(2, radioCommands)
        assertEquals(preset.id, snapshot?.appliedRadioPresetID)
        assertEquals(preset.frequencyKHz, snapshot?.frequencyKHz)
        assertFalse(alert.isPresented)
        assertEquals(listOf<String?>(null, null), errorMessage)
    }

    private companion object {
        const val NOT_CONNECTED: String = "No radio connected"
        val ORIGINAL_SNAPSHOT = RadioDeviceSnapshot(
            frequencyKHz = 1L,
            bandwidthHz = 125_000L,
            spreadingFactor = 7,
            codingRate = 5,
        )
    }
}
