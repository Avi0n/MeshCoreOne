package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.RoomContactPeerFrameCase.CHAT
import one.meshcore.shared.persistence.RoomContactPeerFrameCase.NODE
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomContactPeerFrameTest {
    @Test fun nodeFramePreservesNewerAvatarAndConfirmedFavorite() = exercise(NODE, edited = true, cancelled = false)

    @Test fun canceledNodeFramePreservesCompleteNewerState() = exercise(NODE, edited = true, cancelled = true)

    @Test fun nodeFrameOrdinaryPolicy() = exercise(NODE, edited = false, cancelled = false)

    @Test fun chatFramePreservesNewerAvatarAndConfirmedFavorite() = exercise(CHAT, edited = true, cancelled = false)

    @Test fun canceledChatFramePreservesCompleteNewerState() = exercise(CHAT, edited = true, cancelled = true)

    @Test fun chatFrameOrdinaryPolicy() = exercise(CHAT, edited = false, cancelled = false)

    @Test fun nodeNewKeysKeepPartitionDefaultsAndFirstIdentity() = insert(RoomContactPeerFrameCase.NODE)

    @Test fun chatNewKeysKeepPartitionDefaultsAndFirstIdentity() = insert(RoomContactPeerFrameCase.CHAT)

    private fun insert(kind: RoomContactPeerFrameCase) {
        val fixture = RoomContactPeerFrameInsertFixture(kind)
        try {
            runBlocking { withTimeout(RoomContactPeerFrameFixture.TIMEOUT_MS) { fixture.exercise() } }
        } finally {
            fixture.close()
        }
    }

    private fun exercise(kind: RoomContactPeerFrameCase, edited: Boolean, cancelled: Boolean) {
        val fixture = RoomContactPeerFrameFixture(kind)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactPeerFrameFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val unaffected = fixture.rows.survivors()
                    fixture.start()
                    if (edited) fixture.editCurrent()
                    val expected = fixture.expectedSnapshot(edited, cancelled)
                    if (cancelled) fixture.cancelCaller()
                    fixture.finish(edited, cancelled)
                    assertEquals(unaffected, fixture.rows.survivors())
                    assertEquals(
                        expected,
                        fixture.rows.snapshot(),
                        "LAST: sibling frame writer replayed retired or stale state",
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }
}
