package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.DeviceConnectedToOtherAppException
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SavedDeviceConnectTest {
    @Test
    fun aCompletedConnectPresentsNothing() = runBlocking {
        val presented = mutableListOf<Throwable>()
        var connected = false

        runSavedDeviceConnect(
            connect = { connected = true },
            presentFailure = presented::add,
        )

        assertTrue(connected)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun anExceptionIsPresentedAndRethrown() = runBlocking {
        val presented = mutableListOf<Throwable>()
        val rejected = IllegalStateException("radio rejected")

        try {
            runSavedDeviceConnect(
                connect = { throw rejected },
                presentFailure = presented::add,
            )
            fail("expected the connect failure")
        } catch (thrown: IllegalStateException) {
            assertSame(rejected, thrown)
        }

        assertSame(rejected, presented.single())
    }

    @Test
    fun anErrorIsPresentedAndRethrown() = runBlocking {
        val presented = mutableListOf<Throwable>()
        val fault = SavedDeviceConnectProbeFault()

        try {
            runSavedDeviceConnect(
                connect = { throw fault },
                presentFailure = presented::add,
            )
            fail("expected the connect failure")
        } catch (thrown: SavedDeviceConnectProbeFault) {
            assertSame(fault, thrown)
        }

        assertSame(fault, presented.single())
    }

    @Test
    fun anotherAppHoldIsPresentedThenRethrown() = runBlocking {
        val presented = mutableListOf<Throwable>()
        val held = DeviceConnectedToOtherAppException()

        try {
            runSavedDeviceConnect(
                connect = { throw held },
                presentFailure = presented::add,
            )
            fail("expected the other-app failure")
        } catch (thrown: DeviceConnectedToOtherAppException) {
            assertSame(held, thrown)
        }

        assertSame(held, presented.single())
    }

    @Test
    fun aCancelledConnectRethrowsWithoutAPresentation() = runBlocking {
        val presented = mutableListOf<Throwable>()
        val cancelled = CancellationException("superseded")
        var started = false

        try {
            runSavedDeviceConnect(
                connect = {
                    started = true
                    throw cancelled
                },
                presentFailure = presented::add,
            )
            fail("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }

        assertTrue(started)
        assertTrue(presented.isEmpty())
    }

    @Test
    fun anotherAppHoldIsNotAlertedAgain() {
        assertNull(savedDeviceFailureToPresent(DeviceConnectedToOtherAppException()))
    }

    @Test
    fun aConnectFailureIsAlerted() {
        val rejected = IllegalStateException("radio rejected")
        assertSame(rejected, savedDeviceFailureToPresent(rejected))
    }

    @Test
    fun anErrorIsAlerted() {
        val fault = SavedDeviceConnectProbeFault()
        assertSame(fault, savedDeviceFailureToPresent(fault))
    }
}

private class SavedDeviceConnectProbeFault : Error("probe")
