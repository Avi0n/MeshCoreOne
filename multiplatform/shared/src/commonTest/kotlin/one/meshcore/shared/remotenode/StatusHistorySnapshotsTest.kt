package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusHistorySnapshotsTest {
    @Test
    fun savedHistoryWinsOverTheDemo() {
        assertEquals(
            listOf("saved"),
            statusHistorySnapshots(saved = listOf("saved"), useDemo = true, demo = { listOf("demo") }),
        )
    }

    @Test
    fun demoFillsAnEmptyHistoryOnlyWhenRequested() {
        assertEquals(
            listOf("demo"),
            statusHistorySnapshots(saved = emptyList(), useDemo = true, demo = { listOf("demo") }),
        )
        assertEquals(
            emptyList(),
            statusHistorySnapshots(saved = emptyList(), useDemo = false, demo = { listOf("demo") }),
        )
    }
}
