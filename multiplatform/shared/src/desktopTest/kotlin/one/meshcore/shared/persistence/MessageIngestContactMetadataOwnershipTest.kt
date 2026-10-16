package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageIngestContactMetadataOwnershipTest {
    @Test fun incomingMessagePreservesNewerAvatarAndConfirmedFavorite() = exercise(edited = true)

    @Test fun incomingMessageOrdinaryPolicy() = exercise(edited = false)

    private fun exercise(edited: Boolean) {
        val fixture = RoomIngestContactMetadataFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomIngestContactMetadataFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val unaffected = fixture.rows.survivors()
                    fixture.start()
                    if (edited) fixture.editCurrent()
                    fixture.prepareExpected()
                    fixture.finish(edited)
                    assertEquals(unaffected, fixture.rows.survivors())
                    assertEquals(
                        fixture.expectedSnapshot(),
                        fixture.rows.snapshot(),
                        "LAST: incoming contact metadata replayed stale local fields",
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }
}
