package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.contact.syncContacts
import one.meshcore.shared.contact.syncContactsForRefresh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionRetiredContactEntryOwnershipTest {
    @Test
    fun retiredGraphRejectsBulkSyncBeforeNewSessionDispatch() = exercise(refresh = false)

    @Test
    fun retiredGraphRejectsManualRefreshBeforeNewSessionDispatch() = exercise(refresh = true)

    private fun exercise(refresh: Boolean) {
        val fixture = ConnectionInitialContactReturnFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(ConnectionInitialContactReturnFixture.TIMEOUT_MS) {
                    val old = fixture.start()
                    val current = fixture.replace(ConnectionInitialContactReturnFixture.DEVICE_B)
                    fixture.joinOld(old)
                    val aBefore = fixture.rows.snapshot(old.container.radioId)
                    val bBefore = fixture.rows.snapshot(current.radioId)
                    val prefs = fixture.preferences()
                    val generation = fixture.manager.connectGeneration
                    val calls = fixture.first.getContactsSince.toList()
                    val rejected = fixture.first.rejectedClosedDispatches
                    val failure = runCatching {
                        if (refresh) {
                            old.container.contactService.syncContactsForRefresh(old.container.radioId)
                        } else {
                            old.container.contactService.syncContacts(old.container.radioId)
                        }
                    }.exceptionOrNull()
                    fixture.assertCurrent(current, ConnectionInitialContactReturnFixture.DEVICE_B, generation)
                    assertEquals(aBefore, fixture.rows.snapshot(old.container.radioId))
                    assertEquals(bBefore, fixture.rows.snapshot(current.radioId))
                    assertEquals(prefs, fixture.preferences())
                    assertTrue(
                        failure is CancellationException &&
                            calls == fixture.first.getContactsSince &&
                            rejected == fixture.first.rejectedClosedDispatches,
                        "LAST: retired entry failure=$failure, rejected=${fixture.first.rejectedClosedDispatches - rejected}",
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }
}
