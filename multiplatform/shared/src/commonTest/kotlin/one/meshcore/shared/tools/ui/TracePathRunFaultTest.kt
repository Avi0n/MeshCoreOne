package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.tools.TracePathViewModel
import one.meshcore.shared.tools.run
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TraceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TracePathRunFaultTest {
    private class TraceRunProbeError : Error("probe")

    @Test
    fun aRunFaultFinishesAndReportsFailure() = runTest {
        val model = model { _, _, _ -> error("db locked") }
        model.run()
        val job = requireNotNull(model.runJob)
        job.join()
        assertFalse(model.state.value.isRunning)
        assertEquals("failed", model.state.value.errorMessage)
    }

    @Test
    fun aCancelledRunFinishesThenPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val model = model { _, _, _ -> throw cancelled }
        model.run()
        val job = requireNotNull(model.runJob)
        var cause: Throwable? = null
        job.invokeOnCompletion { cause = it }
        job.join()
        assertSame(cancelled, cause)
        assertFalse(model.state.value.isRunning)
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun aRunErrorFinishesThenPropagates() = runTest {
        val thrown = mutableListOf<Throwable>()
        val fault = TraceRunProbeError()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
                thrown += error
            },
        )
        try {
            val model = model(scope) { _, _, _ -> throw fault }
            model.run()
            requireNotNull(model.runJob).join()
            assertSame(fault, thrown.single())
            assertFalse(model.state.value.isRunning)
            assertNull(model.state.value.errorMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aSuccessfulRunPublishesThenFinishes() = runTest {
        val result = TraceResult(emptyList(), 1, true, null, PATH, HASH_SIZE)
        val model = model { _, _, _ -> result }
        model.run()
        requireNotNull(model.runJob).join()
        assertTrue(model.state.value.result?.success == true)
        assertEquals(1, model.state.value.batchResults.size)
        assertFalse(model.state.value.isRunning)
    }

    private fun TestScope.model(scope: CoroutineScope = backgroundScope, runner: TracePathRunner): TracePathViewModel =
        TracePathViewModel(
            scope,
            TracePathViewModel.Dependencies(
                runner = { runner },
                builder = TracePathBuilder.Dependencies(contactService = { null }, radioId = { null }),
                deviceHashMode = { null },
                deviceLocation = { null },
                savedPathStore = null,
                messages = { TracePathViewModel.Messages("empty", "failed", "%d", "load failed") },
            ),
        ).also { it.builder.loadSavedPath(PATH, HASH_SIZE) }

    private companion object {
        val PATH = byteArrayOf(0x01)
        const val HASH_SIZE = 1
    }
}
