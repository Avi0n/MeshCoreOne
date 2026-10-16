package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A failed CLI send drops the pending request and maps the failure. Cancellation keeps the thrown
 * instance after dropping the request. An error is mapped the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliSendFaultTest {
    @Test
    fun aCancelledCliSendKeepsTheThrownInstanceAndDropsTheRequest() = runTest {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> {
            harness.cli.performCLIExchange(
                harness.publicKey,
                harness.prefix,
                "get name",
                acceptsAnyResponse = true,
                timeout = 1.seconds,
            )
        }

        assertSame(stopped, thrown)
        assertTrue(harness.cli.pendingCLIRequests.isEmpty())
        assertEquals(1, harness.radio.sendCommandCalls.size)
    }

    @Test
    fun aCliSendFailureDropsTheRequestAndMapsToConnectionLost() = runTest {
        val harness = harness(CliSendFailure())

        val thrown = assertFailsWith<RemoteNodeError.SessionError> {
            harness.cli.performCLIExchange(
                harness.publicKey,
                harness.prefix,
                "get name",
                acceptsAnyResponse = true,
                timeout = 1.seconds,
            )
        }

        assertEquals(MeshError.ConnectionLost("radio"), thrown.error)
        assertTrue(harness.cli.pendingCLIRequests.isEmpty())
        assertEquals(1, harness.radio.sendCommandCalls.size)
    }

    @Test
    fun aCliSendErrorIsMappedAndDoesNotEscape() = runTest {
        val harness = harness(CliSendProbeError())

        val thrown = assertFailsWith<RemoteNodeError.SessionError> {
            harness.cli.performCLIExchange(
                harness.publicKey,
                harness.prefix,
                "get name",
                acceptsAnyResponse = true,
                timeout = 1.seconds,
            )
        }

        assertEquals(MeshError.ConnectionLost("probe"), thrown.error)
        assertTrue(harness.cli.pendingCLIRequests.isEmpty())
        assertEquals(1, harness.radio.sendCommandCalls.size)
    }

    private fun TestScope.harness(failure: Throwable): CliSendHarness {
        val radio = FakeNodeSession().apply { sendCommandError = failure }
        val publicKey = testPublicKey()
        val cli = NodeCliCoordinator(radio, InMemoryRemoteNodeStore(), InMemoryNodePasswordStore(), backgroundScope)
        return CliSendHarness(cli, radio, publicKey, publicKey.take(PUBLIC_KEY_PREFIX_LENGTH))
    }
}

private class CliSendHarness(
    val cli: NodeCliCoordinator,
    val radio: FakeNodeSession,
    val publicKey: ByteArray,
    val prefix: List<Byte>,
)

private class CliSendFailure : Exception("radio")

private class CliSendProbeError : Error("probe")
