package one.meshcore.shared.map.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.pingAnnouncement
import one.meshcore.shared.remotenode.testContact
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MapContactDetailDecisionsTest {
    @Test
    fun radioHintAndBusyActionsFollowTheRadio() {
        assertNull(mapContactRadioHint(isRadioReady = true, hint = "Connect a radio"))
        assertEquals("Connect a radio", mapContactRadioHint(isRadioReady = false, hint = "Connect a radio"))
        assertTrue(mapSheetActionEnabled(isRadioReady = true, busy = false))
        assertTrue(!mapSheetActionEnabled(isRadioReady = true, busy = true))
        assertTrue(!mapSheetActionEnabled(isRadioReady = false, busy = false))
    }

    @Test
    fun favoriteLabelAndIconFollowTheRow() {
        assertEquals("Remove", mapFavoriteActionText(isFavorite = true, removeLabel = "Remove", addLabel = "Add"))
        assertEquals("Add", mapFavoriteActionText(isFavorite = false, removeLabel = "Remove", addLabel = "Add"))
        assertEquals("star", mapIconUnlessBusy(busy = false, icon = "star"))
        assertNull(mapIconUnlessBusy(busy = true, icon = "star"))
    }

    @Test
    fun heardKeepsZeroAndAdvertSkipsIt() {
        assertNull(mapTimestampSeconds(timestamp = null, skipNonPositive = false))
        assertEquals(0L, mapTimestampSeconds(timestamp = 0L, skipNonPositive = false))
        assertNull(mapTimestampSeconds(timestamp = 0L, skipNonPositive = true))
        assertEquals(1L, mapTimestampSeconds(timestamp = 1L, skipNonPositive = true))
    }

    @Test
    fun missingPingResultStaysQuiet() {
        val success = ContactPingResult.Success(latencyMs = 1, snrThere = 0.0, snrBack = 0.0)
        val announced = mutableListOf<String>()
        announcePingResult(
            result = null,
            successTemplate = "%d",
            failureMessage = "failed",
            announce = announced::add,
        )
        announcePingResult(
            result = success,
            successTemplate = "%d",
            failureMessage = "failed",
            announce = announced::add,
        )
        assertEquals(
            listOf(pingAnnouncement(result = success, successTemplate = "%d", failureMessage = "failed")),
            announced.toList(),
        )
    }

    @Test
    fun missingServiceDoesNotStartTheAction() = runBlocking {
        val calls = mutableListOf<String>()
        assertNull(
            launchMapContactAction(
                apply = null,
                scope = this,
                callbacks = actionCallbacks(calls),
            ),
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun actionFailureSkipsSuccessAndStillFinishes() = runBlocking {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val fault = IllegalStateException("busy")
        launchMapContactAction(
            apply = { throw fault },
            scope = this,
            callbacks = MapActionCallbacks(
                onStarted = { calls += "start" },
                onSuccess = { calls += "ok" },
                onFailure = { error ->
                    calls += "fail"
                    failures += error
                },
                onFinished = { calls += "done" },
            ),
        )?.join()
        assertEquals(listOf("start", "fail", "done"), calls.toList())
        assertSame(fault, failures.single())
    }

    @Test
    fun actionErrorGoesToFailureAndStillFinishes() = runBlocking {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val fault = MapContactActionProbeError()
        val job = assertNotNull(
            launchMapContactAction(
                apply = { throw fault },
                scope = this,
                callbacks = MapActionCallbacks(
                    onStarted = { calls += "start" },
                    onSuccess = { calls += "ok" },
                    onFailure = { error ->
                        calls += "fail"
                        failures += error
                    },
                    onFinished = { calls += "done" },
                ),
            ),
        )
        job.join()
        assertTrue(!job.isCancelled)
        assertSame(fault, failures.single())
        assertEquals(listOf("start", "fail", "done"), calls.toList())
    }

    @Test
    fun cancellationStillFinishesAndRethrows() = runBlocking {
        val calls = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val cancelled = CancellationException("stop")
        val job = assertNotNull(
            launchMapContactAction(
                apply = { throw cancelled },
                scope = this,
                callbacks = MapActionCallbacks(
                    onStarted = { calls += "start" },
                    onSuccess = { calls += "ok" },
                    onFailure = { error ->
                        calls += "fail"
                        failures += error
                    },
                    onFinished = { calls += "done" },
                ),
            ),
        )
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        job.join()
        assertTrue(job.isCancelled)
        assertSame(cancelled, cause)
        assertEquals(listOf("start", "done"), calls.toList())
        assertTrue(failures.isEmpty())
    }

    @Test
    fun zeroHopPingWaitsForAnIdleRadio() = runBlocking {
        val contact = testContact(name = "Ada")
        val calls = mutableListOf<String>()
        assertNull(
            launchMapZeroHopPing(
                MapZeroHopPing(
                    ping = null,
                    isPinging = false,
                    contact = contact,
                    onStart = { calls += "start" },
                    onResult = { calls += "result" },
                ),
                scope = this,
            ),
        )
        assertNull(
            launchMapZeroHopPing(
                MapZeroHopPing(
                    ping = {
                        calls += "ping"
                        ContactPingResult.NoResponse
                    },
                    isPinging = true,
                    contact = contact,
                    onStart = { calls += "start" },
                    onResult = { calls += "result" },
                ),
                scope = this,
            ),
        )
        launchMapZeroHopPing(
            MapZeroHopPing(
                ping = { pinged ->
                    calls += pinged.name
                    ContactPingResult.NoResponse
                },
                isPinging = false,
                contact = contact,
                onStart = { calls += "start" },
                onResult = { calls += "result" },
            ),
            scope = this,
        )?.join()
        assertEquals(listOf("start", "Ada", "result"), calls.toList())
    }

    @Test
    fun missingRadioContactFallsBackToLocal() = runBlocking {
        val calls = mutableListOf<String>()
        removeMapContactOrLocal(
            removeRemote = { calls += "remote" },
            removeLocal = { calls += "local" },
        )
        assertEquals(listOf("remote"), calls.toList())
        calls.clear()
        removeMapContactOrLocal(
            removeRemote = { throw ContactServiceError.ContactNotFound() },
            removeLocal = { calls += "local" },
        )
        assertEquals(listOf("local"), calls.toList())
        assertFailsWith<IllegalStateException> {
            removeMapContactOrLocal(
                removeRemote = { throw IllegalStateException("busy") },
                removeLocal = { calls += "local" },
            )
        }
        assertEquals(listOf("local"), calls.toList())
    }

    private fun actionCallbacks(calls: MutableList<String>) = MapActionCallbacks(
        onStarted = { calls += "start" },
        onSuccess = { calls += "ok" },
        onFailure = { calls += "fail" },
        onFinished = { calls += "done" },
    )
}

private class MapContactActionProbeError : Error("probe")
