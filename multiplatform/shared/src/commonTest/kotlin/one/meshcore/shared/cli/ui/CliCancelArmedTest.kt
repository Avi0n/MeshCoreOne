package one.meshcore.shared.cli.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS `CLIInputAccessoryView.task(id: isWaiting)` sleeps 150ms before enabling the red stop
 * control, so a fast-finishing command never flashes cancel.
 */
class CliCancelArmedTest {
    @Test
    fun staysDisarmedWhileIdle() {
        assertFalse(cliCancelArmed(isWaiting = false, elapsedMs = 0))
        assertFalse(cliCancelArmed(isWaiting = false, elapsedMs = CLI_CANCEL_REVEAL_MS))
    }

    @Test
    fun staysDisarmedUntilRevealDelay() {
        assertFalse(cliCancelArmed(isWaiting = true, elapsedMs = 0))
        assertFalse(cliCancelArmed(isWaiting = true, elapsedMs = CLI_CANCEL_REVEAL_MS - 1))
    }

    @Test
    fun armsAfterRevealDelayWhileWaiting() {
        assertTrue(cliCancelArmed(isWaiting = true, elapsedMs = CLI_CANCEL_REVEAL_MS))
        assertTrue(cliCancelArmed(isWaiting = true, elapsedMs = CLI_CANCEL_REVEAL_MS + 50))
    }
}
