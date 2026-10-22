// AndroidOnly: WP-207 Source try? paths during connect stay non-fatal and are reported, not silently defaulted.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class BestEffortConnectTest {
    @TestFactory
    fun bestEffortCases() = listOf(
        nativeCase("an auto-add query that times out still connects with the default config and reports the failure") {
            withFixture {
                createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 0x3b) delay(5_000) } } }
                val work = backgroundScope.async { manager.connect(platform.target) }
                advanceTimeBy(10_000); runCurrent()
                work.await()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(0u, manager.connectedDevice!!.autoAddConfig.toUInt())
                assertTrue(diagnostics.any { it is RuntimeDiagnostic.Failure && it.operation == "getAutoAddConfig" })
                assertTrue(manager.shouldAllowConnection(false), "A best-effort query failure must not burn retries or trip the breaker")
                assertEquals(1, radios.size)
            }
        },
        nativeCase("a failing warmUp is reported and the connection still reaches ready") {
            withFixture {
                warmUpFailure = IllegalStateException("purge failed")
                connect()
                assertEquals(1, warmed)
                assertTrue(diagnostics.any { it is RuntimeDiagnostic.Failure && it.operation == "maintenance.warmUp" })
                assertEquals(1, radios.size)
            }
        },
    )
}
