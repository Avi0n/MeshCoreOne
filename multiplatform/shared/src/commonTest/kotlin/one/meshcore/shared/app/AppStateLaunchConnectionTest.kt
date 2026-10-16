package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppStateLaunchConnectionTest {
    @Test
    fun aCancelledConnectStopsFallback() = runTest {
        val stopped = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            launchConnectionAccepted { throw stopped }
        }
        assertSame(stopped, thrown)
    }

    @Test
    fun anOrdinaryFailureAllowsFallback() = runTest {
        assertFalse(launchConnectionAccepted { error("link down") })
    }

    @Test
    fun aSuccessfulConnectIsAccepted() = runTest {
        assertTrue(launchConnectionAccepted {})
    }
}
