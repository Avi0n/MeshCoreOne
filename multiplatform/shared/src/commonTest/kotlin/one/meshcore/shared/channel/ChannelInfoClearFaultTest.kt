package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Clear-messages faults. A service error stays that error. Any other failure becomes a session error.
 * Cancellation leaves the busy flag set and keeps the thrown instance.
 */
class ChannelInfoClearFaultTest {
    @Test
    fun aCancelledClearRethrowsTheSameExceptionAndLeavesTheBusyFlag() = runTest {
        val cancelled = CancellationException("superseded")
        val controller = clearController { throw cancelled }
        var cleared = false

        val thrown = assertFailsWith<CancellationException> { controller.clearMessages { cleared = true } }

        assertSame(cancelled, thrown)
        assertTrue(controller.state.value.isClearingMessages)
        assertNull(controller.state.value.error)
        assertEquals(false, cleared)
    }

    @Test
    fun aServiceErrorStaysThatErrorAndClearsTheBusyFlag() = runTest {
        val failure = ChannelServiceError.SaveFailed("db locked")
        val controller = clearController { throw failure }
        var cleared = false

        controller.clearMessages { cleared = true }

        val shown = assertIs<ChannelActionError.Service>(controller.state.value.error)
        assertSame(failure, shown.error)
        assertEquals(false, controller.state.value.isClearingMessages)
        assertEquals(false, cleared)
    }

    @Test
    fun aStoreExceptionBecomesASessionErrorAndClearsTheBusyFlag() = runTest {
        val failure = ChannelClearStoreException("socket closed")
        val controller = clearController { throw failure }
        var cleared = false

        controller.clearMessages { cleared = true }

        val shown = assertIs<ChannelActionError.Service>(controller.state.value.error)
        val session = assertIs<ChannelServiceError.SessionError>(shown.error)
        assertSame(failure, session.cause)
        assertEquals(false, controller.state.value.isClearingMessages)
        assertEquals(false, cleared)
    }

    @Test
    fun aStoreErrorBecomesASessionErrorAndClearsTheBusyFlag() = runTest {
        val failure = ChannelClearProbeError()
        val controller = clearController { throw failure }
        var cleared = false

        controller.clearMessages { cleared = true }

        val shown = assertIs<ChannelActionError.Service>(controller.state.value.error)
        val session = assertIs<ChannelServiceError.SessionError>(shown.error)
        assertSame(failure, session.cause)
        assertEquals(false, controller.state.value.isClearingMessages)
        assertEquals(false, cleared)
    }
}

private class ChannelClearStoreException(message: String) : Exception(message)

private class ChannelClearProbeError : Error("probe")

private fun clearController(onDelete: () -> Nothing): ChannelInfoController {
    val store = object : ChannelStore by InMemoryChannelStore() {
        override suspend fun deleteMessagesForChannel(radioID: Uuid, channelIndex: Int) {
            onDelete()
        }
    }
    return ChannelInfoController(
        channelDto(
            id = ChannelTestIds.fresh(),
            radioID = ChannelTestIds.RADIO,
            index = 2,
            name = "Ops",
        ),
        ChannelInfoController.Dependencies(
            channelService = { testChannelService(store = store) },
            radioId = { ChannelTestIds.RADIO },
            persistNotificationLevel = { _, _ -> },
            persistPinned = { _, _ -> },
            persistFloodScope = { _, _ -> },
        ),
    )
}
