package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class ToolsCliChannelLoadTest {
    @Test
    fun aSuccessfulReadReturnsTheChannels() = runTest {
        assertEquals(listOf("public"), loadToolsCliChannels { listOf("public") })
    }

    @Test
    fun aMissingServiceStaysNull() = runTest {
        assertNull(loadToolsCliChannels<String> { null })
    }

    @Test
    fun aFailedReadReturnsAnEmptyList() = runTest {
        assertEquals(emptyList<String>(), loadToolsCliChannels<String> { error("db locked") })
    }

    @Test
    fun aCancelledReadPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            loadToolsCliChannels<String> { throw cancelled }
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun aReadErrorPropagates() = runTest {
        val fault = ToolsCliChannelProbeError()
        val thrown = assertFailsWith<ToolsCliChannelProbeError> {
            loadToolsCliChannels<String> { throw fault }
        }

        assertSame(fault, thrown)
    }
}

private class ToolsCliChannelProbeError : Error("probe")
