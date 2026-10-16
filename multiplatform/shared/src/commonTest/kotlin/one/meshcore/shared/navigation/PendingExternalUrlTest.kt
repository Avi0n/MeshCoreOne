package one.meshcore.shared.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Mirrors the iOS `PendingExternalURLTests`: staging, ready-before-submit, and drain-exactly-once. */
class PendingExternalUrlTest {
    @Test
    fun `A URL submitted before ready is held, not routed, until markReady`() {
        val pending = PendingExternalUrl()
        val routed = mutableListOf<String>()
        pending.submit("meshcore://map?lat=1&lon=2")
        assertTrue(routed.isEmpty()) // no sink yet → held
        pending.markReady { routed += it }
        assertEquals(listOf("meshcore://map?lat=1&lon=2"), routed)
    }

    @Test
    fun `markReady routes a held URL exactly once`() {
        val pending = PendingExternalUrl()
        val routed = mutableListOf<String>()
        pending.submit("meshcore://map?lat=1&lon=2")
        pending.markReady { routed += it }
        // A second markReady (e.g. a re-run launch task) must not re-route the drained URL.
        pending.markReady { routed += it }
        assertEquals(1, routed.size)
    }

    @Test
    fun `A URL submitted after ready routes immediately`() {
        val pending = PendingExternalUrl()
        val routed = mutableListOf<String>()
        pending.markReady { routed += it }
        pending.submit("meshcore://contact/add?name=x")
        assertEquals(listOf("meshcore://contact/add?name=x"), routed)
    }
}
