package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AnsweredRadioReadTest {
    @Test
    fun aCancelledRadioReadPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val warnings = mutableListOf<String>()
        val thrown = assertFailsWith<CancellationException> {
            readAnsweredRadioCommand(warnings::add, FAILURE) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aRadioReadValueIsAnswered() = runTest {
        val warnings = mutableListOf<String>()
        val read = readAnsweredRadioCommand(warnings::add, FAILURE) { "region" }
        val value = assertIs<AnsweredRadioRead.Value<String>>(read)
        assertEquals("region", value.value)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aNullRadioAnswerStaysAnswered() = runTest {
        val warnings = mutableListOf<String>()
        val read = readAnsweredRadioCommand(warnings::add, FAILURE) { null }
        val value = assertIs<AnsweredRadioRead.Value<String?>>(read)
        assertEquals(null, value.value)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun aFailedRadioReadIsLoggedAndUnanswered() = runTest {
        val warnings = mutableListOf<String>()
        val read = readAnsweredRadioCommand(warnings::add, FAILURE) { error("radio down") }
        assertIs<AnsweredRadioRead.Failed>(read)
        assertEquals(listOf("Default flood scope read failed: radio down"), warnings)
    }

    @Test
    fun aRadioReadErrorPropagates() = runTest {
        val warnings = mutableListOf<String>()
        val probe = AnsweredRadioProbeError()
        val thrown = assertFailsWith<AnsweredRadioProbeError> {
            readAnsweredRadioCommand(warnings::add, FAILURE) { throw probe }
        }
        assertSame(probe, thrown)
        assertTrue(warnings.isEmpty())
    }
}

private class AnsweredRadioProbeError : Error("probe")

private const val FAILURE = "Default flood scope read failed"
