// AndroidOnly: WP-207 Runtime seams consumed by WP-206 pairing and connectivity: auth latch, device edits, health and switch failures.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSeamTest {
    private fun RuntimeFixture.healthFailure(): Throwable =
        diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>().last { it.operation == "health.reconnect" }.cause

    private suspend fun RuntimeFixture.connectThenLose() {
        connect()
        links.single().callbacks!!.onDisconnected(null); test.runCurrent()
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertTrue(manager.connectionIntent.wantsConnection)
    }

    @TestFactory
    fun nativeCases() = listOf(
        // C-02. Native port of WP-206-owned ConnectionManagerAuthFailureRoutingTests::clearing the surfaced-auth latch lets the same device re-alert().
        nativeCase("clearing the surfaced-auth latch lets the same device re-alert") {
            withFixture {
                connect()
                val id = platform.target.deviceId
                val callbacks = links.single().callbacks!!
                callbacks.onDisconnected(LinkFailure.AuthenticationFailed()); runCurrent()
                assertEquals(listOf(id), authFailures)
                // The foreground path clears the latch so a still-invalid bond re-surfaces.
                manager.clearSurfacedAuthenticationFailure()
                callbacks.onDisconnected(LinkFailure.AuthenticationFailed()); runCurrent()
                assertEquals(listOf(id, id), authFailures)
            }
        },
        // C-03: Swift updateDevice(with:) and the guarded connected-device edits.
        nativeCase("replacing the connected device updates the current generation's radio") {
            withFixture {
                connect()
                val renamed = manager.connectedDevice!!.copy(nodeName = "Renamed", clientRepeat = true)
                manager.replaceConnectedDevice(renamed)
                assertEquals(renamed, manager.connectedDevice)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
            }
        },
        nativeCase("replacing the connected device is ignored for a retired generation and its rebuilt successor") {
            withFixture {
                connect()
                val stale = manager.connectedDevice!!.copy(nodeName = "Stale")
                val callbacks = links.single().callbacks!!
                callbacks.onAutoReconnecting("dropped"); runCurrent()
                manager.replaceConnectedDevice(stale)
                assertNull(manager.connectedDevice)
                callbacks.onReconnected(); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(2, services.size)
                assertEquals("TestNode", manager.connectedDevice!!.nodeName)
            }
        },
        nativeCase("replacing the connected device is ignored for a different device id or radio") {
            withFixture {
                connect()
                val live = manager.connectedDevice!!
                manager.replaceConnectedDevice(live.copy(id = UUID.randomUUID(), nodeName = "Other id"))
                manager.replaceConnectedDevice(live.copy(radioId = RadioId(UUID.randomUUID()), nodeName = "Other radio"))
                assertEquals(live, manager.connectedDevice)
            }
        },
        nativeCase("replacing the connected device is ignored with no connection") {
            withFixture {
                manager.replaceConnectedDevice(DeviceDTO(radioId = RadioId(UUID.randomUUID()), publicKey = publicKey, nodeName = "Ghost"))
                assertNull(manager.connectedDevice)
                connect(); manager.disconnect()
                manager.replaceConnectedDevice(DeviceDTO(radioId = RadioId(UUID.randomUUID()), publicKey = publicKey, nodeName = "Ghost"))
                assertNull(manager.connectedDevice)
            }
        },
        // C-04: Swift attemptOpportunisticReconnect logs a failed foreground reconnect and returns.
        nativeCase("device not found during the foreground health reconnect is reported without throwing") {
            withFixture {
                connectThenLose()
                platform.registryActive = true; platform.registered = false
                manager.appDidBecomeActive()
                assertIs<ConnectionError.DeviceNotFound>(healthFailure())
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertTrue(authFailures.isEmpty())
            }
        },
        nativeCase("an unresolvable health reconnect target is reported without throwing") {
            withFixture {
                connectThenLose()
                platform.resolvesTargets = false
                manager.appDidBecomeActive()
                assertIs<ConnectionError.DeviceNotFound>(healthFailure())
                assertEquals(1, radios.size)
            }
        },
        // Swift checkWiFiConnectionHealth: `try? fetchDevice`, and a failed foreground reconnect is logged.
        nativeCase("a failed WiFi foreground reconnect is reported without throwing out of appDidBecomeActive") {
            withFixture {
                manager.connect(ConnectionTarget.WiFi("localhost", 5000u)); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP); runCurrent()
                assertTrue(manager.connectionIntent.wantsConnection)
                // The persisted radio is a WiFi device, so the foreground WiFi check reconnects to it.
                val id = UUID.randomUUID(); val radio = RadioId(UUID.randomUUID())
                devices.rows[id] = DeviceDTO(id = id, radioId = radio, publicKey = publicKey, nodeName = "WiFi",
                    connectionMethods = listOf(ConnectionMethod.WiFi("localhost", 5000u)).snapshot())
                last.persist(id, radio, "WiFi")
                createRadio = { TestRadio().also { it.connectFailure = IllegalStateException("access point gone") } }
                manager.appDidBecomeActive()
                val wifiFailure = diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>().single { it.operation == "health.wifiReconnect" }
                assertEquals("access point gone", wifiFailure.cause.message)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        nativeCase("cancellation of the foreground health reconnect still propagates") {
            withFixture {
                connectThenLose()
                val gate = CompletableDeferred<Unit>()
                createRadio = { TestRadio().also { it.beforeConnect = { gate.await() } } }
                val health = backgroundScope.async { runCatching { manager.appDidBecomeActive() } }; runCurrent()
                assertEquals(2, radios.size)
                manager.disconnect(); runCurrent()
                assertIs<CancellationException>(health.await().exceptionOrNull())
                gate.complete(Unit)
            }
        },
        nativeCase("authentication failure during the foreground health reconnect still surfaces once") {
            withFixture {
                connectThenLose()
                createRadio = { TestRadio().also { it.connectFailure = LinkFailure.AuthenticationFailed() } }
                manager.appDidBecomeActive()
                assertEquals(listOf(platform.target.deviceId), authFailures)
                assertIs<LinkFailure.AuthenticationFailed>(healthFailure())
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        // C-05: Swift switchDevice catch fires onConnectionLost after cleanup and transport disconnect.
        nativeCase("failed pair-while-connected switch notifies connection lost once after teardown") {
            withFixture {
                connect()
                platform.registryActive = true; platform.registered = false
                onLost = {
                    assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                    assertNull(manager.connectedDevice)
                    assertEquals(1, radios.single().closes); assertEquals(1, services.single().teardowns)
                }
                assertFailsWith<ConnectionError.DeviceNotFound> { manager.connect(target(), true, true) }
                assertEquals(1, lossCount)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        nativeCase("failed connect without a live radio does not notify connection lost") {
            withFixture {
                platform.registryActive = true; platform.registered = false
                assertFailsWith<ConnectionError.DeviceNotFound> { manager.connect(target(), true, true) }
                assertEquals(0, lossCount)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
    )
}
