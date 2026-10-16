package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals

class RemoteCLICommandRewriterTest {
    private val nowSeconds = 1_786_722_487L
    private val expected = "time 1786722487"

    @Test
    fun clockSyncBecomesTimeWithTheHostEpoch() {
        assertEquals(expected, RemoteCLICommandRewriter.rewrite("clock sync", nowSeconds))
    }

    @Test
    fun clockSyncMatchIsCaseAndWhitespaceInsensitive() {
        listOf("CLOCK SYNC", "  clock   sync  ", "Clock Sync").forEach { command ->
            assertEquals(expected, RemoteCLICommandRewriter.rewrite(command, nowSeconds))
        }
    }

    @Test
    fun otherCommandsAreLeftUnchanged() {
        listOf("clock", "time 123", "clock sync extra", "sync_time", "st").forEach { command ->
            assertEquals(command, RemoteCLICommandRewriter.rewrite(command, nowSeconds))
        }
    }

    @Test
    fun preEpochDatesSaturateToZero() {
        assertEquals("time 0", RemoteCLICommandRewriter.rewrite("clock sync", -1000L))
    }
}
