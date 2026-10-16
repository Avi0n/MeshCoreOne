package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomChatContactMetadataOwnershipTest {
    @Test fun lastMessagePreservesNewerPreferences() = exercise(RoomChatContactMetadataCase.LAST_MESSAGE, edited = true)

    @Test fun lastMessageCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataCase.LAST_MESSAGE, edited = true, cancelled = true)

    @Test fun lastMessageOrdinaryPolicy() = exercise(RoomChatContactMetadataCase.LAST_MESSAGE)

    @Test fun lastMessageMissingDoesNotInsert() = exercise(RoomChatContactMetadataCase.LAST_MESSAGE, missing = true)

    @Test fun unreadPreservesNewerPreferences() = exercise(RoomChatContactMetadataCase.UNREAD, edited = true)

    @Test fun unreadCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataCase.UNREAD, edited = true, cancelled = true)

    @Test fun unreadOrdinaryPolicy() = exercise(RoomChatContactMetadataCase.UNREAD)

    @Test fun unreadMissingDoesNotInsert() = exercise(RoomChatContactMetadataCase.UNREAD, missing = true)

    @Test fun mentionPreservesNewerPreferences() = exercise(RoomChatContactMetadataCase.MENTION, edited = true)

    @Test fun mentionCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataCase.MENTION, edited = true, cancelled = true)

    @Test fun mentionOrdinaryPolicy() = exercise(RoomChatContactMetadataCase.MENTION)

    @Test fun mentionMissingDoesNotInsert() = exercise(RoomChatContactMetadataCase.MENTION, missing = true)

    @Test fun heardPreservesNewerPreferences() = exercise(RoomChatContactMetadataCase.HEARD, edited = true)

    @Test fun heardCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataCase.HEARD, edited = true, cancelled = true)

    @Test fun heardOrdinaryPolicy() = exercise(RoomChatContactMetadataCase.HEARD)

    @Test fun heardMissingDoesNotInsert() = exercise(RoomChatContactMetadataCase.HEARD, missing = true)

    @Test fun clearBothPreservesNewerPreferences() = exercise(RoomChatContactMetadataCase.CLEAR_BOTH, edited = true)

    @Test fun clearBothCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataCase.CLEAR_BOTH, edited = true, cancelled = true)

    @Test fun clearBothOrdinaryPolicy() = exercise(RoomChatContactMetadataCase.CLEAR_BOTH)

    @Test fun clearBothMissingDoesNotInsert() = exercise(RoomChatContactMetadataCase.CLEAR_BOTH, missing = true)

    @Test fun clearUnreadPreservesNewerPreferences() = exercise(RoomChatContactMetadataCase.CLEAR_UNREAD, edited = true)

    @Test fun clearUnreadCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataCase.CLEAR_UNREAD, edited = true, cancelled = true)

    @Test fun clearUnreadOrdinaryPolicy() = exercise(RoomChatContactMetadataCase.CLEAR_UNREAD)

    @Test fun clearUnreadMissingDoesNotInsert() = exercise(RoomChatContactMetadataCase.CLEAR_UNREAD, missing = true)

    @Test fun recomputePreservesNewerPreferences() = exercise(RoomChatContactMetadataCase.RECOMPUTE, edited = true)

    @Test fun recomputeCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataCase.RECOMPUTE, edited = true, cancelled = true)

    @Test fun recomputeOrdinaryPolicy() = exercise(RoomChatContactMetadataCase.RECOMPUTE)

    @Test fun recomputeMissingDoesNotInsert() = exercise(RoomChatContactMetadataCase.RECOMPUTE, missing = true)

    @Test fun recomputeReadsMessageSavedBeforeWriterAdmission() = exercise(
        RoomChatContactMetadataCase.RECOMPUTE,
        history = true,
    )

    @Test fun olderHeardStillReconcilesDiscover() = exercise(
        RoomChatContactMetadataCase.HEARD,
        mode = RoomChatContactMetadataCase.HeardMode.OLDER,
    )

    @Test fun equalHeardStillReconcilesDiscover() = exercise(
        RoomChatContactMetadataCase.HEARD,
        mode = RoomChatContactMetadataCase.HeardMode.EQUAL,
    )

    @Test fun nullHeardBecomesStoredZero() = exercise(
        RoomChatContactMetadataCase.HEARD,
        mode = RoomChatContactMetadataCase.HeardMode.NULL_ZERO,
    )

    private fun exercise(
        kind: RoomChatContactMetadataCase,
        edited: Boolean = false,
        cancelled: Boolean = false,
        missing: Boolean = false,
        history: Boolean = false,
        mode: RoomChatContactMetadataCase.HeardMode = RoomChatContactMetadataCase.HeardMode.NEW,
    ) {
        val fixture = RoomChatContactMetadataFixture(kind, mode)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomChatContactMetadataFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val unaffected = fixture.rows.survivors()
                    val gated = !missing &&
                        mode != RoomChatContactMetadataCase.HeardMode.OLDER &&
                        mode != RoomChatContactMetadataCase.HeardMode.EQUAL
                    val expectedBefore = if (!gated) fixture.expectedSnapshot(cancelled, missing) else null
                    fixture.start(missingTarget = missing, gated = gated)
                    if (edited) fixture.editCurrent()
                    if (history) fixture.changeHistory()
                    val expected = expectedBefore ?: fixture.expectedSnapshot(cancelled, missing)
                    if (cancelled) fixture.cancelCaller()
                    fixture.finish(edited, cancelled)
                    assertEquals(unaffected, fixture.rows.survivors())
                    assertEquals(
                        expected,
                        fixture.snapshot(),
                        "LAST: chat contact metadata replayed stale or canceled state",
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }
}
