package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.BODY
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionInitialBacklogMaterializerFixture.Companion.TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionInitialBacklogRetentionTest {
    @Test
    fun retiredLookupRetainsCompleteOrphanBeforeFreshSameRadioReconnect() {
        val f = ConnectionInitialBacklogMaterializerFixture()
        try {
            runBlocking(f.dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    val old = f.start()
                    val b = f.replace(DEVICE_B)
                    f.settleCancelledLookup(old)
                    f.rows.enrich(b.radioId, b.selfPublicKey)
                    val foreign = f.rows.snapshot(b.radioId)
                    f.joinOld(old)
                    f.assertCurrent(b, DEVICE_B, f.manager.connectGeneration)
                    assertEquals(foreign, f.rows.snapshot(b.radioId))
                    val received = f.database.messageDao().listByRadio(old.container.radioId).single()
                    assertEquals(BODY, received.text)
                    f.assertIncoming(received, old.container.radioId, received.contactID)
                    f.noOutgoing()
                    assertEquals(
                        listOf(null, emptyList<Any>()),
                        listOf(received.contactID, f.database.contactDao().listByRadio(old.container.radioId)),
                        "LAST: retired lookup failed to retain the popped DM as an orphan without importing its frame",
                    )
                    val fresh = f.replace(DEVICE_A)
                    assertEquals(old.container.radioId, fresh.radioId)
                    assertEquals(foreign, f.rows.snapshot(b.radioId))
                    val kept = f.database.messageDao().listByRadio(fresh.radioId).single { it.id == received.id }
                    f.assertIncoming(kept, fresh.radioId, kept.contactID)
                    assertEquals(received.text, kept.text)
                    assertTrue(kept.contactID == null || kept.contactID == f.contact(fresh.radioId).id)
                    f.noOutgoing()
                }
            }
        } finally {
            f.close()
        }
    }
}
