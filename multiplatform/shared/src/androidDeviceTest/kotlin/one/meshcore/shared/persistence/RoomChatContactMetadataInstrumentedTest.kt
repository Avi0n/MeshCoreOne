package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class RoomChatContactMetadataInstrumentedTest {
    @get:org.junit.Rule val testName = org.junit.rules.TestName()

    @Test fun lastMessagePreservesNewerPreferences() = exercise(
        RoomChatContactMetadataNativeCase.LAST_MESSAGE,
        edited = true,
    )

    @Test fun lastMessageCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataNativeCase.LAST_MESSAGE, edited = true, cancelled = true)

    @Test fun lastMessageOrdinaryPolicy() = exercise(RoomChatContactMetadataNativeCase.LAST_MESSAGE)

    @Test fun lastMessageMissingDoesNotInsert() = exercise(
        RoomChatContactMetadataNativeCase.LAST_MESSAGE,
        missing = true,
    )

    @Test fun unreadPreservesNewerPreferences() = exercise(RoomChatContactMetadataNativeCase.UNREAD, edited = true)

    @Test fun unreadCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataNativeCase.UNREAD, edited = true, cancelled = true)

    @Test fun unreadOrdinaryPolicy() = exercise(RoomChatContactMetadataNativeCase.UNREAD)

    @Test fun unreadMissingDoesNotInsert() = exercise(RoomChatContactMetadataNativeCase.UNREAD, missing = true)

    @Test fun mentionPreservesNewerPreferences() = exercise(RoomChatContactMetadataNativeCase.MENTION, edited = true)

    @Test fun mentionCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataNativeCase.MENTION, edited = true, cancelled = true)

    @Test fun mentionOrdinaryPolicy() = exercise(RoomChatContactMetadataNativeCase.MENTION)

    @Test fun mentionMissingDoesNotInsert() = exercise(RoomChatContactMetadataNativeCase.MENTION, missing = true)

    @Test fun heardPreservesNewerPreferences() = exercise(RoomChatContactMetadataNativeCase.HEARD, edited = true)

    @Test fun heardCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataNativeCase.HEARD, edited = true, cancelled = true)

    @Test fun heardOrdinaryPolicy() = exercise(RoomChatContactMetadataNativeCase.HEARD)

    @Test fun heardMissingDoesNotInsert() = exercise(RoomChatContactMetadataNativeCase.HEARD, missing = true)

    @Test fun clearBothPreservesNewerPreferences() = exercise(
        RoomChatContactMetadataNativeCase.CLEAR_BOTH,
        edited = true,
    )

    @Test fun clearBothCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataNativeCase.CLEAR_BOTH, edited = true, cancelled = true)

    @Test fun clearBothOrdinaryPolicy() = exercise(RoomChatContactMetadataNativeCase.CLEAR_BOTH)

    @Test fun clearBothMissingDoesNotInsert() = exercise(RoomChatContactMetadataNativeCase.CLEAR_BOTH, missing = true)

    @Test fun clearUnreadPreservesNewerPreferences() = exercise(
        RoomChatContactMetadataNativeCase.CLEAR_UNREAD,
        edited = true,
    )

    @Test fun clearUnreadCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataNativeCase.CLEAR_UNREAD, edited = true, cancelled = true)

    @Test fun clearUnreadOrdinaryPolicy() = exercise(RoomChatContactMetadataNativeCase.CLEAR_UNREAD)

    @Test fun clearUnreadMissingDoesNotInsert() = exercise(
        RoomChatContactMetadataNativeCase.CLEAR_UNREAD,
        missing = true,
    )

    @Test fun recomputePreservesNewerPreferences() = exercise(
        RoomChatContactMetadataNativeCase.RECOMPUTE,
        edited = true,
    )

    @Test fun recomputeCanceledQueuedCallerPreservesState() =
        exercise(RoomChatContactMetadataNativeCase.RECOMPUTE, edited = true, cancelled = true)

    @Test fun recomputeOrdinaryPolicy() = exercise(RoomChatContactMetadataNativeCase.RECOMPUTE)

    @Test fun recomputeMissingDoesNotInsert() = exercise(RoomChatContactMetadataNativeCase.RECOMPUTE, missing = true)

    @Test fun recomputeReadsMessageSavedBeforeWriterAdmission() = exercise(
        RoomChatContactMetadataNativeCase.RECOMPUTE,
        history = true,
    )

    @Test fun olderHeardStillReconcilesDiscover() = exercise(
        RoomChatContactMetadataNativeCase.HEARD,
        mode = RoomChatContactMetadataNativeCase.HeardMode.OLDER,
    )

    @Test fun equalHeardStillReconcilesDiscover() = exercise(
        RoomChatContactMetadataNativeCase.HEARD,
        mode = RoomChatContactMetadataNativeCase.HeardMode.EQUAL,
    )

    @Test fun nullHeardBecomesStoredZero() = exercise(
        RoomChatContactMetadataNativeCase.HEARD,
        mode = RoomChatContactMetadataNativeCase.HeardMode.NULL_ZERO,
    )

    private fun exercise(
        kind: RoomChatContactMetadataNativeCase,
        edited: Boolean = false,
        cancelled: Boolean = false,
        missing: Boolean = false,
        history: Boolean = false,
        mode: RoomChatContactMetadataNativeCase.HeardMode = RoomChatContactMetadataNativeCase.HeardMode.NEW,
    ) {
        val fixture = RoomChatContactMetadataNativeFixture(kind, mode)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomChatContactMetadataNativeFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val unaffected = fixture.rows.survivors()
                    val gated = !missing &&
                        mode != RoomChatContactMetadataNativeCase.HeardMode.OLDER &&
                        mode != RoomChatContactMetadataNativeCase.HeardMode.EQUAL
                    val expectedBefore = if (!gated) fixture.expectedSnapshot(cancelled, missing) else null
                    fixture.start(missingTarget = missing, gated = gated)
                    if (edited) fixture.editCurrent()
                    if (history) fixture.changeHistory()
                    val expected = expectedBefore ?: fixture.expectedSnapshot(cancelled, missing)
                    if (cancelled) fixture.cancelCaller()
                    fixture.finish(cancelled)
                    val survivors = fixture.rows.survivors()
                    assertTrue("Survivor rows preserved", unaffected == survivors)
                    val after = fixture.snapshot()
                    fixture.record(testName.methodName, expected == after, unaffected == survivors)
                    assertTrue("LAST: native chat metadata state differs", expected == after)
                }
            }
        } finally {
            fixture.close(testName.methodName)
        }
    }
}
