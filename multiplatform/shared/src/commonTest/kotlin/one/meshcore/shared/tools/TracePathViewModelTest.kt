package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TraceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TracePathViewModelTest {
    @Test
    fun hidingKeepsTheRunAndPathAndCompletionIsConsumedOnce() = runTest {
        val response = CompletableDeferred<TraceResult>()
        var calls = 0
        val model = model { _, _, _ ->
            calls++
            response.await()
        }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.setVisible(true)
        model.run()
        runCurrent()
        model.setVisible(false)
        assertTrue(model.state.value.isRunning)
        assertEquals(1, calls)
        response.complete(success())
        runCurrent()
        assertFalse(model.state.value.isRunning)
        assertFalse(model.consumeStoredResult())
        model.setVisible(true)
        assertTrue(model.consumeStoredResult())
        assertFalse(model.consumeStoredResult())
        assertEquals(1, model.builder.outboundPath.size)
    }

    @Test
    fun aCompletedBatchMemberIsSavedBeforeTheNextMemberCanBeCancelled() = runTest {
        val store = InMemorySavedTracePathStore()
        val saved = store.createSavedTracePath(RADIO, "Saved", PATH, HASH_SIZE, null)
        val second = CompletableDeferred<TraceResult>()
        var calls = 0
        val model = model(store) { _, _, _ -> if (++calls == 1) success() else second.await() }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.reloadSaved()
        model.setBatchEnabled(true)
        model.run()
        runCurrent()
        assertEquals(1, store.fetchSavedTracePath(saved.id)?.runs?.size)
        advanceTimeBy(INTER_TRACE_BUFFER_MS)
        runCurrent()
        assertEquals(2, calls)
        model.cancelRun()
        runCurrent()
        assertEquals(1, store.fetchSavedTracePath(saved.id)?.runs?.size)
        assertEquals(1, model.state.value.batchResults.size)
        assertFalse(model.state.value.isRunning)
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun firstBatchSuccessCanPresentAndLaterFailureDoesNotReplaceIt() = runTest {
        val second = CompletableDeferred<TraceResult>()
        var calls = 0
        val model = model { _, _, _ -> if (++calls == 1) success() else second.await() }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.setBatchEnabled(true)
        model.setVisible(true)
        model.run()
        runCurrent()
        assertTrue(model.state.value.isRunning)
        assertTrue(model.consumeStoredResult())
        advanceTimeBy(INTER_TRACE_BUFFER_MS)
        runCurrent()
        second.complete(TraceResult.failure("No response", PATH, HASH_SIZE))
        runCurrent()
        assertTrue(model.state.value.result?.success == true)
        assertEquals(2, model.state.value.batchResults.size)
        assertFalse(model.consumeStoredResult())
        model.cancelRun()
    }

    @Test
    fun hiddenSheetDisposalKeepsBatchButVisibleDismissalCancelsIt() = runTest {
        var calls = 0
        val model = model { _, _, _ ->
            calls++
            success()
        }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.setVisible(true)
        model.setBatchEnabled(true)
        model.run()
        runCurrent()
        assertTrue(model.consumeStoredResult())
        model.setVisible(false)
        model.dismissResult()
        assertTrue(model.state.value.isRunning)
        advanceTimeBy(INTER_TRACE_BUFFER_MS)
        runCurrent()
        assertEquals(2, calls)
        model.setVisible(true)
        assertTrue(model.consumeStoredResult())
        model.dismissResult()
        runCurrent()
        advanceTimeBy(INTER_TRACE_BUFFER_MS)
        runCurrent()
        assertEquals(2, calls)
        assertFalse(model.state.value.isRunning)
    }

    @Test
    fun retiredNonCooperativeResponseCannotPublishOrFinishAReplacementRun() = runTest {
        val old = CompletableDeferred<TraceResult>()
        val replacement = CompletableDeferred<TraceResult>()
        var calls = 0
        val model = model { _, _, _ ->
            if (++calls == 1) withContext(NonCancellable) { old.await() } else replacement.await()
        }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.run()
        runCurrent()
        model.reset()
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.run()
        runCurrent()
        old.complete(success())
        runCurrent()
        assertTrue(model.state.value.isRunning)
        assertNull(model.state.value.result)
        replacement.complete(success())
        runCurrent()
        assertFalse(model.state.value.isRunning)
        assertEquals(1, model.state.value.batchResults.size)
    }

    @Test
    fun hidingStopsErrorExpiryAndReopeningDoesNotRearmIt() = runTest {
        val model = model { _, _, _ -> success() }
        model.setVisible(true)
        model.setError("Visible")
        advanceTimeBy(ERROR_DELAY_MS / 2)
        model.setVisible(false)
        advanceTimeBy(ERROR_DELAY_MS)
        runCurrent()
        assertEquals("Visible", model.state.value.errorMessage)
        model.setVisible(true)
        advanceTimeBy(ERROR_DELAY_MS)
        runCurrent()
        assertEquals("Visible", model.state.value.errorMessage)
        model.setError("New")
        advanceTimeBy(ERROR_DELAY_MS)
        runCurrent()
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun resetDropsRadioOwnedStateAndKeepsUserOptions() = runTest {
        val model = model { _, _, _ -> success() }
        model.setAutoReturn(false)
        model.setBatchEnabled(true)
        model.setBatchCount(5)
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.setError("Old")
        model.reset()
        assertFalse(model.state.value.autoReturn)
        assertTrue(model.state.value.batchEnabled)
        assertEquals(5, model.state.value.batchCount)
        assertTrue(model.builder.outboundPath.isEmpty())
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun successiveBatchSuccessesUpdateTheResultWithoutAnotherPresentation() = runTest {
        var duration = 0
        val model = model { _, _, _ -> success().copy(durationMs = ++duration) }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.setVisible(true)
        model.setBatchEnabled(true)
        model.run()
        runCurrent()
        assertTrue(model.consumeStoredResult())
        val id = model.state.value.successId
        advanceTimeBy(INTER_TRACE_BUFFER_MS)
        runCurrent()
        assertEquals(2, model.state.value.result?.durationMs)
        assertEquals(id, model.state.value.successId)
        assertFalse(model.consumeStoredResult())
        model.cancelRun()
    }

    @Test
    fun outgoingMountCannotHideItsReplacement() = runTest {
        val model = model { _, _, _ -> success() }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        val old = model.attach()
        model.attach()
        model.detach(old, showingResult = true)
        model.run()
        runCurrent()
        assertTrue(model.consumeStoredResult())
    }

    @Test
    fun capturedBatchProgressControlsPresentationAndDismissalAfterAnOptionChange() = runTest {
        val second = CompletableDeferred<TraceResult>()
        var calls = 0
        var cancelled = false
        val model = model { _, _, _ ->
            if (++calls == 1) {
                success()
            } else {
                try {
                    second.await()
                } catch (error: CancellationException) {
                    cancelled = true
                    throw error
                }
            }
        }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.setVisible(true)
        model.setBatchEnabled(true)
        model.run()
        runCurrent()
        model.setBatchEnabled(false)
        assertTrue(model.consumeStoredResult())
        advanceTimeBy(INTER_TRACE_BUFFER_MS)
        runCurrent()
        model.dismissResult()
        runCurrent()
        assertTrue(cancelled)
        assertFalse(model.state.value.isRunning)
        assertEquals(2, calls)
    }

    private fun TestScope.model(
        store: InMemorySavedTracePathStore? = null,
        runner: suspend (ByteArray, Int, Int) -> TraceResult,
    ): TracePathViewModel = TracePathViewModel(
        backgroundScope,
        TracePathViewModel.Dependencies(
            runner = { runner },
            builder = TracePathBuilder.Dependencies(contactService = { null }, radioId = { RADIO }),
            deviceHashMode = { null },
            deviceLocation = { null },
            savedPathStore = store,
            messages = { TracePathViewModel.Messages("Empty", "Failed", "%d failed", "Load failed") },
        ),
    )

    private fun success() = TraceResult(emptyList(), 10, true, null, PATH, HASH_SIZE)

    private companion object {
        val RADIO = Uuid("00000000-0000-0000-0000-000000000001")
        val PATH = byteArrayOf(0x11)
        const val HASH_SIZE = 1
        const val ERROR_DELAY_MS = 4_000L
    }
}
