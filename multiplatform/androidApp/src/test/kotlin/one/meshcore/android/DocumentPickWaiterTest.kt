package one.meshcore.android

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentPickWaiterTest {
    @Test
    fun leftoverPickIsTakenWithoutAwait() {
        val waiter = DocumentPickWaiter<String>()
        waiter.offer("content://meshcore/backup")
        assertEquals("content://meshcore/backup", waiter.takeLeftover())
        assertNull(waiter.takeLeftover())
    }

    @Test
    fun offerCompletesAnInFlightAwait() = runTest {
        val waiter = DocumentPickWaiter<String>()
        val pending = async { waiter.awaitPick() }
        waiter.offer("content://meshcore/node-config")
        assertEquals("content://meshcore/node-config", pending.await())
    }

    @Test
    fun cancelOfferIsTakenAsNullLeftover() {
        val waiter = DocumentPickWaiter<String>()
        waiter.offer(null)
        assertNull(waiter.takeLeftover())
    }

    @Test
    fun cancelDeliveredBeforeAwaitCompletesThePicker() = runTest {
        val waiter = DocumentPickWaiter<String>()
        waiter.offer(null)
        assertNull(withTimeout(1_000) { waiter.awaitPick() })
    }
}
