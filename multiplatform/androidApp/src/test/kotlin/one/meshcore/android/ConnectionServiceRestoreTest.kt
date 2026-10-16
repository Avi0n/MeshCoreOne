package one.meshcore.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionServiceRestoreTest {
    @Test
    fun aNullStartIntentMeansProcessDeathRestart() {
        assertTrue(shouldReconnectAfterStickyRestart(null))
    }

    @Test
    fun aBootCompletedActionStartsTheService() {
        assertTrue(shouldStartConnectionAfterBoot(ACTION_BOOT_COMPLETED))
        assertTrue(shouldStartConnectionAfterBoot(ACTION_MY_PACKAGE_REPLACED))
        assertFalse(shouldStartConnectionAfterBoot(ACTION_BOOT_RECONNECT))
        assertFalse(shouldStartConnectionAfterBoot(null))
    }

    @Test
    fun aBootReconnectActionRunsActivate() {
        assertTrue(shouldReconnectAfterBootStart(ACTION_BOOT_RECONNECT))
        assertFalse(shouldReconnectAfterBootStart(ACTION_BOOT_COMPLETED))
        assertFalse(shouldReconnectAfterBootStart(null))
    }
}
