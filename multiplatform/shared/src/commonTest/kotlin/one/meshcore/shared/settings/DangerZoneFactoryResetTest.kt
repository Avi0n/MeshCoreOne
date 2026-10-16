package one.meshcore.shared.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DangerZoneFactoryResetTest {
    @Test
    fun unavailableServicesDoNotSendOrForget() = runTest {
        var sent = false
        var forgot = false

        val ok = runDangerZoneFactoryReset(
            servicesAvailable = false,
            sendReset = { sent = true },
            afterExpectedReboot = { forgot = true },
        )

        assertFalse(ok)
        assertFalse(sent)
        assertFalse(forgot)
    }

    @Test
    fun sendTimeoutStillForgetsTheDevice() = runTest {
        var forgot = false

        val ok = runDangerZoneFactoryReset(
            servicesAvailable = true,
            sendReset = { error("radio rebooted before ACK") },
            afterExpectedReboot = { forgot = true },
        )

        assertTrue(ok)
        assertTrue(forgot)
    }

    @Test
    fun successfulSendThenForgets() = runTest {
        val steps = mutableListOf<String>()

        val ok = runDangerZoneFactoryReset(
            servicesAvailable = true,
            sendReset = { steps += "send" },
            afterExpectedReboot = { steps += "forget" },
        )

        assertTrue(ok)
        assertEquals(listOf("send", "forget"), steps)
    }

    @Test
    fun aCancelledSendDoesNotForgetTheDevice() = runTest {
        val cancelled = CancellationException("stopped")
        var forgot = false
        val thrown = assertFailsWith<CancellationException> {
            runDangerZoneFactoryReset(
                servicesAvailable = true,
                sendReset = { throw cancelled },
                afterExpectedReboot = { forgot = true },
            )
        }
        assertSame(cancelled, thrown)
        assertFalse(forgot)
    }

    @Test
    fun aSendErrorDoesNotForgetTheDevice() = runTest {
        val probe = DangerZoneResetProbeError()
        var forgot = false
        val thrown = assertFailsWith<DangerZoneResetProbeError> {
            runDangerZoneFactoryReset(
                servicesAvailable = true,
                sendReset = { throw probe },
                afterExpectedReboot = { forgot = true },
            )
        }
        assertSame(probe, thrown)
        assertFalse(forgot)
    }
}

private class DangerZoneResetProbeError : Error("probe")
