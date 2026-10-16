package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.contact.RecordingCleanupHandler
import one.meshcore.shared.contact.RecordingContactSyncCoordinator
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.contact.updateContactPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomContactPreferenceTransitionTest {
    @Test fun queuedBlockUsesActualPriorStateAfterAnotherBlockCompletes() {
        val first = RoomContactPreferencePeerFixture(RoomContactPreferencePeerCase.BLOCK)
        try {
            runBlocking(first.dispatcher) {
                withTimeout(RoomContactPreferencePeerFixture.TIMEOUT_MS) {
                    first.seed()
                    first.start()
                    val secondCleanup = RecordingCleanupHandler()
                    val secondNotifications = RecordingContactSyncCoordinator()
                    val second = testContactService(
                        store = first.rows.store,
                        cleanup = secondCleanup,
                        syncCoordinator = secondNotifications,
                    )
                    second.updateContactPreferences(first.rows.firstContact, isBlocked = true)
                    assertEquals(1, secondCleanup.invocations.size)
                    assertEquals(1, secondNotifications.contactsChanged)
                    val expected = first.rows.snapshot()
                    assertNull(first.releaseAndJoin())
                    assertEquals(
                        listOf(expected, 1, 2),
                        listOf(
                            first.rows.snapshot(),
                            first.cleanupCount + secondCleanup.invocations.size,
                            first.contactNotificationCount + secondNotifications.contactsChanged,
                        ),
                        "LAST: queued block ignored the actual prior transition",
                    )
                }
            }
        } finally {
            first.close()
        }
    }
}
