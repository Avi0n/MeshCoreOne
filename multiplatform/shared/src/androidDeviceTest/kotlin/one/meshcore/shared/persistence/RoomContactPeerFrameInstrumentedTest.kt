package one.meshcore.shared.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.RoomContactPeerNativeFixture.Kind
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomContactPeerFrameInstrumentedTest {
    @get:Rule val testName = TestName()

    @Test fun contactFramePreservesNewerPreferences() = exercise(Kind.CONTACT, edited = true, cancelled = false)

    @Test fun canceledContactFramePreservesCompleteNewerState() = exercise(
        Kind.CONTACT,
        edited = true,
        cancelled = true,
    )

    @Test fun contactFrameOrdinaryPolicy() = exercise(Kind.CONTACT, edited = false, cancelled = false)

    @Test fun contactNewKeysKeepPartitionDefaultsAndFirstIdentity() = insert(Kind.CONTACT)

    @Test fun nodeFramePreservesNewerPreferences() = exercise(Kind.NODE, edited = true, cancelled = false)

    @Test fun canceledNodeFramePreservesCompleteNewerState() = exercise(Kind.NODE, edited = true, cancelled = true)

    @Test fun nodeFrameOrdinaryPolicy() = exercise(Kind.NODE, edited = false, cancelled = false)

    @Test fun nodeNewKeysKeepPartitionDefaultsAndFirstIdentity() = insert(Kind.NODE)

    @Test fun chatFramePreservesNewerPreferences() = exercise(Kind.CHAT, edited = true, cancelled = false)

    @Test fun canceledChatFramePreservesCompleteNewerState() = exercise(Kind.CHAT, edited = true, cancelled = true)

    @Test fun chatFrameOrdinaryPolicy() = exercise(Kind.CHAT, edited = false, cancelled = false)

    @Test fun chatNewKeysKeepPartitionDefaultsAndFirstIdentity() = insert(Kind.CHAT)

    private fun exercise(kind: Kind, edited: Boolean, cancelled: Boolean) {
        val fixture = RoomContactPeerNativeFixture(kind)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactPeerNativeFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val unaffected = fixture.rows.survivors()
                    fixture.start()
                    if (edited) fixture.editCurrent()
                    val expected = fixture.expectedSnapshot(edited, cancelled)
                    if (cancelled) fixture.cancelCaller()
                    fixture.finish(cancelled)
                    val survivors = fixture.rows.survivors()
                    assertEquals(unaffected, survivors)
                    val after = fixture.rows.snapshot()
                    fixture.record(testName.methodName, expected == after, unaffected == survivors)
                    assertEquals("LAST: native frame writer replayed retired or stale state", expected, after)
                }
            }
        } finally {
            fixture.close(testName.methodName)
        }
    }

    private fun insert(kind: Kind) {
        val fixture = RoomContactPeerNativeInsertFixture(kind)
        try {
            runBlocking {
                withTimeout(RoomContactPeerNativeFixture.TIMEOUT_MS) { fixture.exercise(testName.methodName) }
            }
        } finally {
            fixture.close(testName.methodName)
        }
    }
}
