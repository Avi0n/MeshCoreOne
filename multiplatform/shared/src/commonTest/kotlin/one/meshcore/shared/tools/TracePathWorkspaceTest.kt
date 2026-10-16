package one.meshcore.shared.tools

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.TraceFailureMessages
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TracePathRequest
import one.meshcore.shared.trace.TracePathWaiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TracePathWorkspaceTest {
    @Test
    fun onlyTheCurrentLifecycleOwnerCanRetirePendingWork() = runTest {
        val fixture = Fixture(this)
        fixture.model.run()
        runCurrent()
        fixture.workspace.reset { false }
        runCurrent()
        assertTrue(fixture.model.state.value.isRunning)
        assertEquals(TAG, fixture.waiter.state.value.pendingTag)
        fixture.workspace.reset { true }
        runCurrent()
        assertFalse(fixture.model.state.value.isRunning)
        assertTrue(fixture.model.builder.outboundPath.isEmpty())
        assertNull(fixture.model.state.value.result)
        assertNull(fixture.waiter.state.value.pendingTag)
    }

    @Test
    fun workspaceReattachesTheSameTagAndDeadlineWithoutResending() = runTest {
        val fixture = Fixture(this)
        fixture.model.run()
        runCurrent()
        val deadline = fixture.waiter.state.value.deadlineEpochMs
        val retired = fixture.first
        val replacement = MutableSharedFlow<MeshEvent>()
        fixture.stream = replacement
        fixture.workspace.reattach()
        runCurrent()
        assertEquals(1, fixture.sends)
        assertEquals(TAG, fixture.waiter.state.value.pendingTag)
        assertEquals(deadline, fixture.waiter.state.value.deadlineEpochMs)
        retired.emit(response())
        runCurrent()
        assertTrue(fixture.model.state.value.isRunning)
        replacement.emit(response())
        runCurrent()
        assertTrue(fixture.model.state.value.result?.success == true)
        assertFalse(fixture.model.state.value.isRunning)
    }

    @Test
    fun foregroundClaimsTheOriginalDefaultDeadlineAfterTransientMissingSession() = runTest {
        val fixture = Fixture(this, transientMissingSession = true)
        fixture.stream = null
        fixture.model.run()
        runCurrent()
        assertEquals(TAG, fixture.waiter.state.value.pendingTag)
        assertEquals(DEFAULT_DEADLINE_MS, fixture.waiter.state.value.deadlineEpochMs)
        assertTrue(fixture.model.state.value.isRunning)
        fixture.clock = DEFAULT_DEADLINE_MS
        fixture.workspace.expireDeadline()
        runCurrent()
        assertFalse(fixture.model.state.value.isRunning)
        assertEquals("no reply", fixture.model.state.value.errorMessage)
        assertEquals(1, fixture.model.state.value.batchResults.size)
        fixture.workspace.expireDeadline()
        runCurrent()
        assertEquals(1, fixture.model.state.value.batchResults.size)
        assertEquals(1, fixture.sends)
    }

    private class Fixture(scope: TestScope, transientMissingSession: Boolean = false) {
        val first = MutableSharedFlow<MeshEvent>()
        var stream: Flow<MeshEvent>? = first
        var clock = 0L
        var sends = 0
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ stream }, { RADIO }))
        val workspace = TracePathWorkspace(
            scope.backgroundScope,
            TracePathWorkspace.Dependencies(
                runner = {
                    { path, width, flags ->
                        waiter.run(
                            TracePathRequest(
                                path, width, flags, stream ?: emptyFlow(),
                                sendTrace = { _, _, _ ->
                                    sends++
                                    if (transientMissingSession) {
                                        throw MeshBridgeException(
                                            BridgeErrorCode.NOT_CONNECTED,
                                            "missing",
                                        )
                                    }
                                    MessageSentInfo(0, byteArrayOf(), 0L)
                                },
                                deviceName = "Radio", failureMessages = TraceFailureMessages("no reply", "send failed"),
                                nowMs = { clock }, randomTag = { TAG },
                            ),
                        )
                    }
                },
                builder = TracePathBuilder.Dependencies(contactService = { null }, radioId = { RADIO }),
                deviceHashMode = { null },
                deviceLocation = { null },
                savedPathStore = null,
                recents = null,
            ),
            waiter,
        )
        val model = workspace.bind(TracePathWorkspace.Messages("empty", "failed", "%d failed", "load failed"))
            .also { it.builder.loadSavedPath(byteArrayOf(0x11), 1) }
    }

    private fun response() = MeshEvent.TraceData(TraceInfo(TAG, 0L, 0, 0, emptyList()))

    private companion object {
        const val TAG = 7L
        const val DEFAULT_DEADLINE_MS = 30_000L
        val RADIO = Uuid("00000000-0000-0000-0000-000000000001")
    }
}
