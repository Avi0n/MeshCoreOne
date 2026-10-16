package one.meshcore.shared.cli.ui

import androidx.compose.foundation.lazy.LazyListState
import kotlin.test.Test
import kotlin.test.assertTrue

class CliJumpToBottomTest {
    @Test
    fun emptyOutputCountsAsAtBottom() {
        assertTrue(LazyListState().cliOutputIsAtBottom())
    }
}
