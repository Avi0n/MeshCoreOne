package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinkDisconnectTest {
    @Test
    fun aCompletedDisconnectRethrowsTheHandshakeCancellation() = runTest {
        val handshake = CancellationException("handshake")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            disconnectDeadLink(warnings::add, handshake) { }
        }
        assertSame(handshake, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aCancelledDisconnectStillRethrowsTheHandshakeCancellation() = runTest {
        val handshake = CancellationException("handshake")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            disconnectDeadLink(warnings::add, handshake) { throw CancellationException("link") }
        }
        assertSame(handshake, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aDisconnectFailureIsLoggedAndTheHandshakeCancellationLeaves() = runTest {
        val handshake = CancellationException("handshake")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            disconnectDeadLink(warnings::add, handshake) { error("radio down") }
        }
        assertSame(handshake, thrown)
        assertEquals(listOf("$LINK_DISCONNECT_FAILED: radio down"), warnings)
        assertEquals("Link disconnect failed", LINK_DISCONNECT_FAILED)
    }

    @Test
    fun aDisconnectErrorIsLoggedAndTheHandshakeCancellationLeaves() = runTest {
        val handshake = CancellationException("handshake")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            disconnectDeadLink(warnings::add, handshake) { throw LinkDisconnectProbeError() }
        }
        assertSame(handshake, thrown)
        assertEquals(listOf("$LINK_DISCONNECT_FAILED: probe"), warnings)
    }
}

private class LinkDisconnectProbeError : Error("probe")
