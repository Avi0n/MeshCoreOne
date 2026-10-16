package one.meshcore.android

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeConfigDocumentPickWaiterTest {
    @Test
    fun synchronousResultHasExactlyOneOwner() = runTest {
        val waiter = NodeConfigDocumentPickWaiter<String>()
        var launches = 0
        val result = waiter.pick {
            launches += 1
            assertTrue(waiter.offer("selected"))
        }
        assertEquals("selected", result)
        assertEquals(1, launches)
        assertFalse(waiter.offer("duplicate"))
    }

    @Test
    fun synchronousNullSettlesTheReservation() = runTest {
        val waiter = NodeConfigDocumentPickWaiter<String>()
        assertNull(waiter.pick { assertTrue(waiter.offer(null)) })
        assertEquals("next", waiter.pick { waiter.offer("next") })
    }

    @Test
    fun delayedResultCompletesOnlyTheOriginalAwait() = runTest {
        val waiter = NodeConfigDocumentPickWaiter<String>()
        val original = async(start = CoroutineStart.UNDISPATCHED) { waiter.pick {} }
        assertFalse(original.isCompleted)
        assertTrue(waiter.offer("selected"))
        assertEquals("selected", original.await())
        assertFalse(waiter.offer("duplicate"))
    }

    @Test
    fun retiredValueCannotBeReassignedToAReplacement() = runTest {
        val waiter = NodeConfigDocumentPickWaiter<String>()
        val original = async(start = CoroutineStart.UNDISPATCHED) { waiter.pick {} }
        original.cancelAndJoin()
        var launches = 0
        assertTrue(runCatching { waiter.pick { launches += 1 } }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, launches)
        assertTrue(waiter.offer("retired"))
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { waiter.pick { launches += 1 } }
        assertFalse(replacement.isCompleted)
        assertTrue(waiter.offer("fresh"))
        assertEquals("fresh", replacement.await())
        assertEquals(1, launches)
    }

    @Test
    fun retiredNullCannotCancelAReplacement() = runTest {
        val waiter = NodeConfigDocumentPickWaiter<String>()
        val original = async(start = CoroutineStart.UNDISPATCHED) { waiter.pick {} }
        original.cancelAndJoin()
        assertTrue(runCatching { waiter.pick { error("must not launch") } }.exceptionOrNull() is IllegalStateException)
        assertTrue(waiter.offer(null))
        val replacement = async(start = CoroutineStart.UNDISPATCHED) { waiter.pick {} }
        assertFalse(replacement.isCompleted)
        assertTrue(waiter.offer("fresh"))
        assertEquals("fresh", replacement.await())
    }

    @Test
    fun aFailedLaunchReleasesItsExactReservation() = runTest {
        val waiter = NodeConfigDocumentPickWaiter<String>()
        val failure = runCatching { waiter.pick { throw IllegalArgumentException("launch") } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertFalse(waiter.offer("unclaimed"))
        assertEquals("fresh", waiter.pick { waiter.offer("fresh") })
    }
}
