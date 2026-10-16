package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class RunContactSessionTest {
    @Test
    fun aCancelledContactCommandPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            runContactSession(mapDeviceCode = null) { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun aContactCommandSuccessReturnsTheValue() = runTest {
        assertEquals("loaded", runContactSession(mapDeviceCode = null) { "loaded" })
    }

    @Test
    fun aTypedContactErrorIsRethrown() = runTest {
        val failure = ContactServiceError.NotConnected()
        val thrown = assertFailsWith<ContactServiceError.NotConnected> {
            runContactSession(mapDeviceCode = null) { throw failure }
        }
        assertSame(failure, thrown)
    }

    @Test
    fun aSessionFailureBecomesSessionError() = runTest {
        val failure = IllegalStateException("radio down")
        val thrown = assertFailsWith<ContactServiceError.SessionError> {
            runContactSession(mapDeviceCode = null) { throw failure }
        }
        assertSame(failure, thrown.cause)
    }

    @Test
    fun aContactCommandErrorBecomesSessionError() = runTest {
        val probe = ContactSessionProbeError()
        val thrown = assertFailsWith<ContactServiceError.SessionError> {
            runContactSession(mapDeviceCode = null) { throw probe }
        }
        assertSame(probe, thrown.cause)
    }

    @Test
    fun aMappedFirmwareCodeBecomesThatContactError() = runTest {
        val thrown = assertFailsWith<ContactServiceError.ContactNotFound> {
            runContactSession(
                mapDeviceCode = { code ->
                    if (code == FIRMWARE_NOT_FOUND) ContactServiceError.ContactNotFound() else null
                },
            ) { throw ContactDeviceException(FIRMWARE_NOT_FOUND) }
        }
        assertEquals("Contact not found on device", thrown.message)
    }

    @Test
    fun anUnmappedFirmwareCodeBecomesSessionError() = runTest {
        val failure = ContactDeviceException(FIRMWARE_NOT_FOUND)
        val thrown = assertFailsWith<ContactServiceError.SessionError> {
            runContactSession(mapDeviceCode = { null }) { throw failure }
        }
        assertSame(failure, thrown.cause)
    }

    @Test
    fun aSessionCommandLeavesAFirmwareCodeAsSessionError() = runTest {
        val failure = ContactDeviceException(FIRMWARE_NOT_FOUND)
        val thrown = assertFailsWith<ContactServiceError.SessionError> {
            runContactSession(mapDeviceCode = null) { throw failure }
        }
        assertSame(failure, thrown.cause)
    }
}

private class ContactSessionProbeError : Error("probe")

private const val FIRMWARE_NOT_FOUND = 2
