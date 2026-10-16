package one.meshcore.shared.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every chat catch routes through [ChatErrorMessages.userFacing], the counterpart of the Swift
 * `error.userFacingMessage`. A case it does not recognise reaches the user as the exception's own
 * developer English, which is what this replaced.
 */
class ChatErrorMessagesTest {
    private val messages = ChatErrorMessages.developerEnglish().copy(
        notConnected = "NOT_CONNECTED",
        sendFailed = "SEND_FAILED",
        invalidRecipient = "INVALID_RECIPIENT",
        messageTooLong = "TOO_LONG",
        sessionError = { if (it is IllegalStateException) "SESSION" else null },
    )

    @Test
    fun everyMessageServiceFailureHasItsOwnWords() {
        val resolved = listOf(
            MessageServiceException.NotConnected(),
            MessageServiceException.SendFailed("socket"),
            MessageServiceException.InvalidRecipient(),
            MessageServiceException.MessageTooLong(),
        ).map { messages.userFacing(it, FALLBACK) }

        assertEquals(listOf("NOT_CONNECTED", "SEND_FAILED", "INVALID_RECIPIENT", "TOO_LONG"), resolved)
        assertTrue(resolved.none { it == FALLBACK })
    }

    /** iOS shows the shipped sentence and drops the reason, which is developer text. */
    @Test
    fun aSendFailureDoesNotLeakItsReason() {
        val text = messages.userFacing(MessageServiceException.SendFailed("null cursor at row 3"), FALLBACK)

        assertTrue("cursor" !in text)
    }

    /** A wrapped session failure defers to the bridge copy, as `sessionError` does on iOS. */
    @Test
    fun aWrappedSessionFailureUsesTheBridgeCopy() {
        val wrapped = MessageServiceException.SessionError(IllegalStateException("boom"))

        assertEquals("SESSION", messages.userFacing(wrapped, FALLBACK))
    }

    /** The bridge has no words for every failure; the caller's own fallback covers the rest. */
    @Test
    fun anUnrecognisedSessionFailureFallsBack() {
        val wrapped = MessageServiceException.SessionError(RuntimeException("boom"))

        assertEquals(FALLBACK, messages.userFacing(wrapped, FALLBACK))
    }

    @Test
    fun anUnrelatedFailureFallsBack() {
        assertEquals(FALLBACK, messages.userFacing(RuntimeException("boom"), FALLBACK))
    }

    /**
     * The exception messages are developer English by design; the resolved copy must not be reusing them,
     * which is the defect this type exists to prevent.
     */
    @Test
    fun theShippedCopyIsNotTheExceptionText() {
        val shipped = ChatErrorMessages.developerEnglish()
        val raw = MessageServiceException.SendFailed("socket").message

        assertTrue(shipped.userFacing(MessageServiceException.SendFailed("socket"), FALLBACK) != raw)
    }

    private companion object {
        const val FALLBACK = "FALLBACK"
    }
}
