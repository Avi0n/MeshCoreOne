package one.meshcore.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class RunReportingExceptionTest {
    @Test
    fun successReturnsTheBlockResult() = runTest {
        val result = runReportingException(onFailure = { "failed" }) { "ok" }

        assertEquals("ok", result)
    }

    @Test
    fun exceptionReturnsTheFailureResult() = runTest {
        val error = IllegalStateException("parse")
        var reported: Exception? = null

        val result = runReportingException(
            onFailure = { failure ->
                reported = failure
                "failed"
            },
        ) {
            throw error
        }

        assertSame(error, reported)
        assertEquals("failed", result)
    }

    @Test
    fun cancellationPropagatesUnchanged() = runTest {
        val cancellation = CancellationException("stopped")
        var reported = false

        val thrown = assertFailsWith<CancellationException> {
            runReportingException(onFailure = {
                reported = true
            }) { throw cancellation }
        }

        assertSame(cancellation, thrown)
        assertFalse(reported)
    }

    @Test
    fun errorPropagatesUnchanged() = runTest {
        val failure = ProbeError("full")
        var reported = false

        val thrown = assertFailsWith<ProbeError> {
            runReportingException<Unit>(onFailure = { reported = true }) { throw failure }
        }

        assertSame(failure, thrown)
        assertFalse(reported)
    }

    @Test
    fun failureCallbackThrowPropagates() = runTest {
        val failure = IllegalStateException("report")

        val thrown = assertFailsWith<IllegalStateException> {
            runReportingException<Unit>(onFailure = { throw failure }) {
                throw IllegalArgumentException("original")
            }
        }

        assertSame(failure, thrown)
    }

    @Test
    fun throwableReportsAnError() = runTest {
        val failure = ProbeError("full")
        var reported: Throwable? = null

        val result = runReportingThrowable(
            onFailure = { error ->
                reported = error
                "failed"
            },
        ) { throw failure }

        assertSame(failure, reported)
        assertEquals("failed", result)
    }

    @Test
    fun exceptionFallbackLetsAnErrorPropagate() = runTest {
        val failure = ProbeError("full")
        var usedFallback = false

        val thrown = assertFailsWith<ProbeError> {
            runExceptionFallback(onFailure = {
                usedFallback = true
                "failed"
            }) { throw failure }
        }

        assertSame(failure, thrown)
        assertFalse(usedFallback)
    }

    @Test
    fun throwableFallbackReportsThenContinues() = runTest {
        val failure = ProbeError("full")
        var reported: Throwable? = null

        val result = runThrowableFallback(
            onFailure = { reported = it },
            fallback = { "continued" },
        ) { throw failure }

        assertSame(failure, reported)
        assertEquals("continued", result)
    }

    @Test
    fun throwableFallbackStopsWhenTheReportThrows() = runTest {
        val report = IllegalStateException("report")
        var continued = false

        val thrown = assertFailsWith<IllegalStateException> {
            runThrowableFallback<String>(
                onFailure = { throw report },
                fallback = {
                    continued = true
                    "continued"
                },
            ) { throw IllegalArgumentException("original") }
        }

        assertSame(report, thrown)
        assertFalse(continued)
    }

    @Test
    fun throwableResultPropagatesCancellationUnchanged() = runTest {
        val cancellation = CancellationException("stopped")
        var usedFallback = false

        val thrown = assertFailsWith<CancellationException> {
            runThrowableResult(
                onFailure = {
                    usedFallback = true
                    "failed"
                },
            ) { throw cancellation }
        }

        assertSame(cancellation, thrown)
        assertFalse(usedFallback)
    }

    @Test
    fun throwableResultUsesFallbackForErrors() = runTest {
        val result = runThrowableResult(onFailure = { "failed" }) {
            throw ProbeError("full")
        }

        assertEquals("failed", result)
    }
}

private class ProbeError(message: String) : Error(message)
