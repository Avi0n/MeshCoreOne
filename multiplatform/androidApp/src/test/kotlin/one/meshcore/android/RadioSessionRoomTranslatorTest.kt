package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.persistence.runRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RadioSessionRoomTranslatorTest {
    @Test
    fun aCancelledRoomReadPropagates() = runBlocking {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        try {
            runRoom(warnings::add, FAILURE, fallback = { "fallback" }) {
                throw cancelled
            }
            fail("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(cancelled, thrown)
        }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aRoomReadExceptionIsLoggedAndSkipped() = runBlocking {
        val warnings = mutableListOf<String>()
        val value = runRoom(warnings::add, FAILURE, fallback = { null }) {
            error("room down")
        }
        assertNull(value)
        assertEquals(listOf("Radio session Room read failed: room down"), warnings)
    }

    @Test
    fun aRoomReadErrorPropagates() = runBlocking {
        val warnings = mutableListOf<String>()
        val probe = RoomProbeError()
        try {
            runRoom(warnings::add, FAILURE, fallback = { "fallback" }) {
                throw probe
            }
            fail("expected error")
        } catch (thrown: RoomProbeError) {
            assertSame(probe, thrown)
        }
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aRoomReadSuccessSkipsTheFallback() = runBlocking {
        val warnings = mutableListOf<String>()
        val value = runRoom(warnings::add, FAILURE, fallback = { "fallback" }) { "heltec" }
        assertEquals("heltec", value)
        assertTrue(warnings.isEmpty())
    }

    private class RoomProbeError : Error("probe")

    private companion object {
        const val FAILURE = "Radio session Room read failed"
    }
}
