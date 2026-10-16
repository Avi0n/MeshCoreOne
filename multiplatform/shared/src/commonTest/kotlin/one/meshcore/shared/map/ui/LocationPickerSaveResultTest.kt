package one.meshcore.shared.map.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LocationPickerSaveResultTest {
    @Test
    fun aSuccessfulWriteDismissesThePicker() = runTest {
        var saved = false

        val result = locationPickerSaveResult { saved = true }

        assertIs<LocationPickerSaveResult.Dismiss>(result)
        assertTrue(saved)
    }

    @Test
    fun aFailedWriteKeepsThePickerOpen() = runTest {
        val fault = IllegalStateException("No radio connected")

        val result = locationPickerSaveResult { throw fault }

        val stay = assertIs<LocationPickerSaveResult.Stay>(result)
        assertSame(fault, stay.error)
    }

    @Test
    fun aCancelledWritePropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            locationPickerSaveResult { throw cancelled }
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun anErrorPropagates() = runTest {
        val fault = LocationPickerProbeError()
        val thrown = assertFailsWith<LocationPickerProbeError> {
            locationPickerSaveResult { throw fault }
        }

        assertSame(fault, thrown)
    }
}

private class LocationPickerProbeError : Error("probe")
