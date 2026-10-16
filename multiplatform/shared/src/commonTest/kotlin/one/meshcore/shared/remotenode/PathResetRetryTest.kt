package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PathResetRetryTest {
    private val key = ByteArray(32) { 1 }

    @Test
    fun aTimeoutResetsThePathAndRetriesOnce() = runTest {
        var attempts = 0
        var reset = false
        val result = performWithPathResetOnTimeout(
            publicKey = key,
            resetPath = { reset = true },
            operationName = "status",
            operation = {
                attempts += 1
                if (attempts == 1) throw MeshError.Timeout
                "ok"
            },
        )
        assertEquals("ok", result)
        assertTrue(reset)
        assertEquals(2, attempts)
    }

    @Test
    fun aNonTimeoutIsNotRetried() = runTest {
        var reset = false
        assertFailsWith<IllegalStateException> {
            performWithPathResetOnTimeout(
                publicKey = key,
                resetPath = { reset = true },
                operationName = "status",
                operation = { error("no") },
            )
        }
        assertTrue(!reset)
    }

    @Test
    fun aCancelledOperationPropagatesWithoutReset() = runTest {
        val cancelled = CancellationException("superseded")
        var reset = false
        val thrown = assertFailsWith<CancellationException> {
            performWithPathResetOnTimeout(
                publicKey = key,
                resetPath = { reset = true },
                operationName = "status",
                operation = { throw cancelled },
            )
        }
        assertSame(cancelled, thrown)
        assertTrue(!reset)
    }

    @Test
    fun anOperationErrorPropagatesWithoutReset() = runTest {
        val probe = PathResetProbeError()
        var reset = false
        val thrown = assertFailsWith<PathResetProbeError> {
            performWithPathResetOnTimeout(
                publicKey = key,
                resetPath = { reset = true },
                operationName = "status",
                operation = { throw probe },
            )
        }
        assertSame(probe, thrown)
        assertTrue(!reset)
    }

    @Test
    fun aFailedResetRethrowsTheOriginalTimeout() = runTest {
        val original = MeshBridgeException(BridgeErrorCode.TIMEOUT, "stale path")
        val thrown = assertFailsWith<MeshBridgeException> {
            performWithPathResetOnTimeout(
                publicKey = key,
                resetPath = { error("reset failed") },
                operationName = "status",
                operation = { throw original },
            )
        }
        assertEquals(original, thrown)
    }

    @Test
    fun aCancelledResetPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        var resets = 0
        val thrown = assertFailsWith<CancellationException> {
            performWithPathResetOnTimeout(
                publicKey = key,
                resetPath = {
                    resets += 1
                    throw cancelled
                },
                operationName = "status",
                operation = { throw MeshError.Timeout },
            )
        }
        assertSame(cancelled, thrown)
        assertEquals(1, resets)
    }

    @Test
    fun aResetErrorRethrowsTheOriginalTimeout() = runTest {
        val original = MeshError.Timeout
        val thrown = assertFailsWith<MeshError.Timeout> {
            performWithPathResetOnTimeout(
                publicKey = key,
                resetPath = { throw PathResetProbeError() },
                operationName = "status",
                operation = { throw original },
            )
        }
        assertSame(original, thrown)
    }

    @Test
    fun aTimeoutRecordsTheIosMeshTimeoutDump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        var attempts = 0
        performWithPathResetOnTimeout(
            publicKey = key,
            resetPath = {},
            operationName = "status",
            logger = PersistentLogger(this, category = "BinaryProtocol", record = { recorded += it }),
            operation = {
                attempts += 1
                if (attempts == 1) throw MeshError.Timeout
                "ok"
            },
        )
        val line = recorded.single { it.message.contains("status: mesh timeout") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("BinaryProtocol", line.category)
        assertTrue(line.message.contains("resetting path to flood"))
    }

    @Test
    fun aFailedResetRecordsTheIosPathResetFailedDump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        assertFailsWith<MeshError.Timeout> {
            performWithPathResetOnTimeout(
                publicKey = key,
                resetPath = { error("radio busy") },
                operationName = "telemetry",
                logger = PersistentLogger(this, category = "BinaryProtocol", record = { recorded += it }),
                operation = { throw MeshError.Timeout },
            )
        }
        val line = recorded.single { it.message.contains("telemetry: path reset failed") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("BinaryProtocol", line.category)
        assertTrue(line.message.contains("radio busy"))
    }
}

private class PathResetProbeError : Error("probe")
