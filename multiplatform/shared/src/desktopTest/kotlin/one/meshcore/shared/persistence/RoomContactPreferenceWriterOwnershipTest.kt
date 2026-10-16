package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomContactPreferenceWriterOwnershipTest {
    @Test fun activeNicknameWriterPreservesConcurrentAvatar() = exercise {
        seed()
        val unaffected = rows.survivors()
        startNickname()
        editAvatar()
        val expected = expectedNicknameSnapshot()
        releaseAndJoin()
        assertEffects(2)
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot(), "LAST: active nickname edit replayed an older avatar")
    }

    @Test fun ordinaryNicknameWriterTrimsOnlyNickname() = exercise {
        seed()
        val unaffected = rows.survivors()
        val expected = expectedNicknameSnapshot()
        startNickname()
        releaseAndJoin()
        assertEffects(1)
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot())
    }

    @Test fun missingNicknameTargetDoesNotInsertOrNotify() = exercise {
        seed()
        val before = rows.snapshot()
        missingTarget()
        assertEquals(before, rows.snapshot())
    }

    private fun exercise(block: suspend RoomContactPreferenceWriterFixture.() -> Unit) {
        val fixture = RoomContactPreferenceWriterFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactPreferenceWriterFixture.TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
