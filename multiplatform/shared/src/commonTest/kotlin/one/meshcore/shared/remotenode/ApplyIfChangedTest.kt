package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplyIfChangedTest {
    @Test
    fun nullCurrentCountsAsUnchanged() = runTest {
        val commands = mutableListOf<String>()
        val commits = mutableListOf<String>()

        val applied = applyIfChanged(
            current = null,
            original = "old",
            command = { value: String ->
                commands += value
                "set $value"
            },
            succeeded = { command ->
                commands += command
                true
            },
            commit = { commits += it },
        )

        assertTrue(applied)
        assertEquals(emptyList(), commands)
        assertEquals(emptyList(), commits)
    }

    @Test
    fun equalValueDoesNotSend() = runTest {
        val commands = mutableListOf<String>()

        val applied = applyIfChanged(
            current = "same",
            original = "same",
            command = { value: String ->
                commands += value
                "set $value"
            },
            succeeded = { true },
            commit = { error("commit") },
        )

        assertTrue(applied)
        assertEquals(emptyList(), commands)
    }

    @Test
    fun rejectedCommandDoesNotCommit() = runTest {
        val commits = mutableListOf<Int>()

        val applied = applyIfChanged(
            current = 3,
            original = 1,
            command = { "set flood.max $it" },
            succeeded = { command ->
                assertEquals("set flood.max 3", command)
                false
            },
            commit = { commits += it },
        )

        assertFalse(applied)
        assertEquals(emptyList(), commits)
    }

    @Test
    fun acceptedCommandCommitsTheCurrentValue() = runTest {
        val commits = mutableListOf<Int>()

        val applied = applyIfChanged(
            current = 3,
            original = 1,
            command = { "set flood.max $it" },
            succeeded = { true },
            commit = { commits += it },
        )

        assertTrue(applied)
        assertEquals(listOf(3), commits)
    }

    @Test
    fun sendFailurePropagatesWithoutCommit() = runTest {
        val failure = RemoteNodeError.SendFailed("radio unavailable")
        val commits = mutableListOf<Int>()

        val thrown = assertFailsWith<RemoteNodeError.SendFailed> {
            applyIfChanged(
                current = 3,
                original = 1,
                command = { "set flood.max $it" },
                succeeded = { throw failure },
                commit = { commits += it },
            )
        }

        assertSame(failure, thrown)
        assertEquals(emptyList(), commits)
    }
}
