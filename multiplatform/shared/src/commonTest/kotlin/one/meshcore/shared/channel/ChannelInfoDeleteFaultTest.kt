package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Channel delete faults. A service error stays that error. Any other failure becomes a session error.
 * Cancellation leaves the busy flag set and keeps the thrown instance.
 */
class ChannelInfoDeleteFaultTest {
    @Test
    fun aCancelledDeleteRethrowsTheSameExceptionAndLeavesTheBusyFlag() = runTest {
        val cancelled = CancellationException("superseded")
        val controller = deleteController { throw cancelled }
        var deleted = false

        val thrown = assertFailsWith<CancellationException> { controller.delete { deleted = true } }

        assertSame(cancelled, thrown)
        assertTrue(controller.state.value.isDeleting)
        assertNull(controller.state.value.error)
        assertEquals(false, deleted)
    }

    @Test
    fun aServiceErrorStaysThatErrorAndClearsTheBusyFlag() = runTest {
        val failure = ChannelServiceError.SaveFailed("db locked")
        val controller = deleteController { throw failure }
        var deleted = false

        controller.delete { deleted = true }

        val shown = assertIs<ChannelActionError.Service>(controller.state.value.error)
        assertSame(failure, shown.error)
        assertEquals(false, controller.state.value.isDeleting)
        assertEquals(false, deleted)
    }

    @Test
    fun aStoreExceptionBecomesASessionErrorAndClearsTheBusyFlag() = runTest {
        val failure = ChannelDeleteStoreException("socket closed")
        val controller = deleteController { throw failure }
        var deleted = false

        controller.delete { deleted = true }

        val shown = assertIs<ChannelActionError.Service>(controller.state.value.error)
        val session = assertIs<ChannelServiceError.SessionError>(shown.error)
        assertSame(failure, session.cause)
        assertEquals(false, controller.state.value.isDeleting)
        assertEquals(false, deleted)
    }

    @Test
    fun aStoreErrorBecomesASessionErrorAndClearsTheBusyFlag() = runTest {
        val failure = ChannelDeleteProbeError()
        val controller = deleteController { throw failure }
        var deleted = false

        controller.delete { deleted = true }

        val shown = assertIs<ChannelActionError.Service>(controller.state.value.error)
        val session = assertIs<ChannelServiceError.SessionError>(shown.error)
        assertSame(failure, session.cause)
        assertEquals(false, controller.state.value.isDeleting)
        assertEquals(false, deleted)
    }
}

private class ChannelDeleteStoreException(message: String) : Exception(message)

private class ChannelDeleteProbeError : Error("probe")

private fun deleteController(onFetch: () -> Nothing): ChannelInfoController {
    val store = object : ChannelStore by InMemoryChannelStore() {
        override suspend fun fetchChannel(radioID: Uuid, index: Int): ChannelDto? {
            onFetch()
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
