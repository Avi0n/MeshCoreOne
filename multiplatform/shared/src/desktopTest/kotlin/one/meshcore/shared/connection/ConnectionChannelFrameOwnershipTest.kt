package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ConnectionChannelFrameOwnershipTest {
    @Test
    fun oldCompletedBatchCannotReplaceFreshSameRadioOccupantAndHistory() = exercise { f ->
        val current = f.replace()
        val target = f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.TARGET)
        val contrast = f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.CONTRAST)
        val foreign = f.rows.slot(f.foreignRadio, ConnectionChannelFrameRows.TARGET)
        val globals = f.rows.globals()
        val prefs = f.preferences()
        f.releaseOriginal()
        f.assertCurrent(current)
        assertEquals(globals, f.rows.globals())
        assertEquals(contrast, f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.CONTRAST))
        assertEquals(foreign, f.rows.slot(f.foreignRadio, ConnectionChannelFrameRows.TARGET))
        assertEquals(prefs, f.preferences())
        assertEquals(
            target,
            f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.TARGET),
            "LAST: retired channel batch replayed old occupant or erased fresh history; forwarded=${f.first.forwarded}, closedBacklog=${f.first.closedBacklogAdmissions}",
        )
    }

    @Test
    fun liveCompletedBatchRenamesSameSecretAndPreservesLocalStateAndHistory() = exercise { f ->
        val expected = f.rows.seed(f.oldRadio, ConnectionChannelFrameFixture.oldTarget, initial = true)
        val expectedContrast = f.rows.seed(f.oldRadio, ConnectionChannelFrameFixture.contrast, initial = true)
        val target = f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.TARGET)
        val contrast = f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.CONTRAST)
        val all = f.rows.all()
        val globals = f.rows.globals()
        f.releaseOriginal().getOrThrow()
        assertEquals(ConnectionState.Ready(ConnectionChannelFrameFixture.A), f.manager.state.value)
        assertSame(f.oldContainer, f.manager.services)
        val wireExpected = f.rows.channel(expected.copy(name = ConnectionChannelFrameFixture.oldTarget.name))
        assertEquals(listOf(wireExpected) + target.drop(1), f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.TARGET))
        val contrastExpected =
            listOf(f.rows.channel(expectedContrast.copy(name = ConnectionChannelFrameFixture.contrast.name))) +
                contrast.drop(1)
        assertEquals(contrastExpected, f.rows.slot(f.oldRadio, ConnectionChannelFrameRows.CONTRAST))
        assertEquals(all.drop(1), f.rows.all().drop(1))
        assertEquals(globals, f.rows.globals())
        assertEquals(1, f.first.getMessageCount)
        assertEquals(1, f.first.autoFetchStartCount)
        f.rows.assertPendingEmpty()
    }

    private fun exercise(block: suspend (ConnectionChannelFrameFixture) -> Unit) {
        val f = ConnectionChannelFrameFixture()
        try {
            runBlocking(f.dispatcher) {
                withTimeout(ConnectionChannelFrameFixture.TIMEOUT_MS) {
                    f.start()
                    block(f)
                }
            }
        } finally {
            f.close()
        }
    }
}
