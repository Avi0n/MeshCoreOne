package one.meshcore.shared.ui

import one.meshcore.shared.transport.TcpTransportException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connect sheet maps a transport failure to shipped copy; an unmapped BLE/JNI fault uses the
 * settings fallback instead of interpolating exception text.
 */
class TcpTransportErrorTextTest {
    private val cases = listOf(
        TcpTransportException.ConnectionFailed(reason = "refused"),
        TcpTransportException.ConnectionTimeout(),
        TcpTransportException.NotConnected(),
        TcpTransportException.SendFailed(reason = "broken pipe"),
        TcpTransportException.SendTimeout(),
        TcpTransportException.InvalidHost(),
        TcpTransportException.InvalidPort(),
    )

    @Test
    fun everyFailureResolvesToItsOwnMessage() {
        assertEquals(
            cases.size,
            cases.map { it.messageResource() }.toSet().size,
            "two failures share a message, so one of them explains the wrong problem",
        )
    }

    /** The set a caller resolves ahead of the failure has to cover every message, or the lookup answers null. */
    @Test
    fun everyMessageIsResolvableAheadOfTheFailure() {
        cases.forEach { failure ->
            assertTrue(
                failure.messageResource() in TcpTransportErrorText.allResources,
                "${failure::class.simpleName} maps to a message the connect sheet never resolved",
            )
        }
    }

    /**
     * Both quoting messages carry a substitution the socket fills. Left unformatted they reach the user as
     * a literal "%s", which is the failure this mapper exists to prevent.
     */
    @Test
    fun onlyTheTwoQuotingFailuresTakeAReason() {
        val quoting = cases.filter { it.formatArgs().isNotEmpty() }

        assertEquals(
            listOf(TcpTransportErrorText.CONNECTION_FAILED, TcpTransportErrorText.SEND_FAILED),
            quoting.map { it.errorText() },
        )
        assertEquals(listOf(listOf("refused"), listOf("broken pipe")), quoting.map { it.formatArgs() })
    }

    /** The reason has to survive onto the exception, or the formatted message would quote nothing. */
    @Test
    fun aQuotedReasonSurvivesOntoTheFailure() {
        assertEquals("refused", TcpTransportException.ConnectionFailed(reason = "refused").reason)
        assertEquals("broken pipe", TcpTransportException.SendFailed(reason = "broken pipe").reason)
    }

    @Test
    fun aFormattedMessageQuotesTheReasonInsteadOfTheSpecifier() {
        val failure = TcpTransportException.ConnectionFailed(reason = "refused")
        val formatted = formatTemplate("Connection failed: %s", failure.formatArgs())

        assertEquals("Connection failed: refused", formatted)
        assertTrue("%s" !in formatted)
    }

    @Test
    fun mappedErrorTextFormatsAResolvedTemplateAndLeavesTheRestNull() {
        val failure = TcpTransportException.ConnectionFailed(reason = "refused")
        val templates = mapOf(failure.messageResource() to "Connection failed: %s")
        assertEquals(
            "Connection failed: refused",
            mappedErrorText(
                templates,
                failure,
                { it as? TcpTransportException },
                TcpTransportException::messageResource,
                TcpTransportException::formatArgs,
            ),
        )
        assertNull(
            mappedErrorText(
                templates,
                IllegalStateException("radio down"),
                { it as? TcpTransportException },
                TcpTransportException::messageResource,
                TcpTransportException::formatArgs,
            ),
        )
        assertNull(
            mappedErrorText(
                emptyMap(),
                failure,
                { it as? TcpTransportException },
                TcpTransportException::messageResource,
                TcpTransportException::formatArgs,
            ),
        )
    }

    @Test
    fun anUnmappedConnectFaultShowsFallbackNotEnglish() {
        val error = IllegalStateException("radio down")
        assertEquals(
            "connect fail",
            connectFailureMessage(error, transportErrorText = { null }, fallback = { "connect fail" }),
        )
        assertEquals(
            "wifi mapped",
            connectFailureMessage(error, transportErrorText = { "wifi mapped" }, fallback = { "connect fail" }),
        )
    }
}
