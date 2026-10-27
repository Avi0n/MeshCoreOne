// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectRadioIDResolutionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerCircuitBreakerTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerRetryBudgetTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerSessionTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourceConnectionManagerTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("ConnectionManagerCircuitBreakerTests", "closed breaker allows connections") {
            withFixture { assertTrue(manager.shouldAllowConnection(false)) }
        },
        original("ConnectionManagerCircuitBreakerTests", "failure trips the breaker and blocks connections during cooldown") {
            withFixture { manager.recordConnectionFailure(); assertFalse(manager.shouldAllowConnection(false)) }
        },
        original("ConnectionManagerCircuitBreakerTests", "force bypasses an open breaker") {
            withFixture { manager.recordConnectionFailure(); assertTrue(manager.shouldAllowConnection(true)) }
        },
        original("ConnectionManagerCircuitBreakerTests", "elapsed cooldown transitions the breaker to half-open and allows a probe") {
            withFixture {
                manager.recordConnectionFailure(); advanceTimeBy(30_000)
                assertTrue(manager.shouldAllowConnection(false)); assertTrue(manager.shouldAllowConnection(false))
            }
        },
        original("ConnectionManagerCircuitBreakerTests", "failed half-open probe re-opens the breaker") {
            withFixture {
                manager.recordConnectionFailure(); advanceTimeBy(30_000); assertTrue(manager.shouldAllowConnection(false))
                manager.recordConnectionFailure(); assertFalse(manager.shouldAllowConnection(false))
            }
        },
        original("ConnectionManagerCircuitBreakerTests", "successful connection closes the breaker again") {
            withFixture {
                manager.recordConnectionFailure(); manager.recordConnectionSuccess(); assertTrue(manager.shouldAllowConnection(false))
            }
        },
        original("ConnectionManagerCircuitBreakerTests", "repeated failures while open keep the original cooldown start") {
            withFixture {
                manager.recordConnectionFailure(); advanceTimeBy(25_000); manager.recordConnectionFailure(); advanceTimeBy(5000)
                assertTrue(manager.shouldAllowConnection(false))
            }
        },
        original("ConnectionManagerCircuitBreakerTests", "connect rejects with a typed error while the breaker is open") {
            withFixture {
                manager.recordConnectionFailure(); val failure = assertFailsWith<ConnectionError.ConnectionFailed> { manager.connect(platform.target) }
                assertEquals("Connection blocked by circuit breaker (cooling down)", failure.reason)
                assertTrue(radios.isEmpty()); assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        original("ConnectionManagerRetryBudgetTests",
            "connect uses the unverified budget only for a macOS user-initiated tap, the full budget otherwise",
            "(_ testCase : RetryBudgetCase)") {
            for ((registry, force, expected) in listOf(Triple(false, true, 2), Triple(false, false, 4), Triple(true, true, 4))) {
                withFixture {
                    platform.hasSystemPairingRegistry = registry
                    val failure = LinkFailure.ConnectionFailed("unreachable")
                    createRadio = { TestRadio().also { it.connectFailure = failure } }
                    val thrown = assertFailsWith<LinkFailure.ConnectionFailed> { manager.connect(platform.target, false, force) }
                    assertEquals(failure.detail, thrown.detail); assertEquals(expected, radios.size)
                    assertFalse(manager.shouldAllowConnection(false)); assertTrue(radios.all { it.collectors == 0 })
                }
            }
        },
        original("ConnectionManagerRetryBudgetTests", "connect rejects an unconnectable registered device before any transport attempt") {
            withFixture {
                platform.registryActive = true; platform.registered = false; platform.hasSystemPairingRegistry = true
                assertFailsWith<ConnectionError.DeviceNotFound> { manager.connect(platform.target) }
                assertTrue(radios.isEmpty()); assertTrue(manager.shouldAllowConnection(false))
            }
        },
        original("ConnectRadioIDResolutionTests", "Reconnect with a changed BLE id but same publicKey resolves the original radioID and keeps its PendingSends reachable") {
            withFixture {
                connect()
                val first = assertNotNull(manager.connectedDevice); val originalRadio = first.radioId
                val pendingSends = mutableMapOf(originalRadio to mutableListOf("queued before reconnect"))
                manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                val newHandle = target(); assertNotEquals(first.id, newHandle.deviceId)
                connect(newHandle)
                assertEquals(originalRadio, manager.connectedDevice!!.radioId)
                assertEquals(listOf("queued before reconnect"), assertNotNull(pendingSends[manager.connectedDevice!!.radioId]).toList())
                assertEquals(listOf("id", "key", "save", "id", "key", "save", "delete"), devices.calls)
                assertEquals(1, devices.rows.size)
                assertEquals(originalRadio, services.last().token.radioId)
            }
        },
        original("ConnectionManagerSessionTests", "setConnectionState updates to connected") {
            withFixture { manager.setConnectionState(DeviceConnectionState.CONNECTED); assertEquals(DeviceConnectionState.CONNECTED, manager.connectionState) }
        },
        original("ConnectionManagerSessionTests", "setConnectionState updates to disconnected") {
            withFixture {
                manager.setConnectionState(DeviceConnectionState.CONNECTED); manager.setConnectionState(DeviceConnectionState.DISCONNECTED)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState); assertNull(manager.snapshot.value.token)
            }
        },
        original("ConnectionManagerSessionTests", "setConnectedDevice sets device") {
            withFixture {
                connect()
                val before = assertNotNull(manager.connectedDevice)
                val edited = before.copy(nodeName = "TestDevice")
                assertNotEquals(before.nodeName, edited.nodeName)
                manager.replaceConnectedDevice(edited)
                assertEquals(edited, manager.connectedDevice)
                assertEquals(platform.target.deviceId, manager.connectedDevice?.id)
                assertEquals(services.single().token, manager.snapshot.value.token)
            }
        },
        original("ConnectionManagerSessionTests", "setConnectedDevice sets nil") {
            withFixture { connect(); manager.clearConnectedDevice(); assertNull(manager.connectedDevice) }
        },
        original("ConnectionManagerSessionTests", "isTransportAutoReconnecting delegates to stateMachine") {
            withFixture {
                connect(); assertFalse(manager.isTransportAutoReconnecting())
                platform.state = platform.state.copy(autoReconnecting = true); assertTrue(manager.isTransportAutoReconnecting())
            }
        },
        original("ConnectionManagerSessionTests", "activeConnectionAttemptDeviceID prefers session rebuild device") {
            withFixture {
                connect(); val gate = CompletableDeferred<Unit>()
                createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 1) gate.await() } } }
                val work = backgroundScope.async { manager.rebuildSession(platform.target.deviceId) }
                runCurrent(); assertEquals(platform.target.deviceId, manager.activeConnectionAttemptDeviceId)
                assertEquals(platform.target.deviceId, manager.activeReconnectDeviceId)
                gate.complete(Unit); runCurrent(); work.await()
            }
        },
        original("ConnectionManagerSessionTests", "connect to same device returns early while session rebuild is in progress") {
            withFixture {
                connect(); val gate = CompletableDeferred<Unit>()
                createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 1) gate.await() } } }
                val work = backgroundScope.async { manager.rebuildSession(platform.target.deviceId) }
                runCurrent(); val count = radios.size
                assertFalse(work.isCompleted)
                assertEquals(1, radios.last().frames.count { it[0].toInt() == 1 })
                val snapshot = manager.snapshot.value
                manager.connect(platform.target); assertEquals(count, radios.size); assertEquals(platform.target.deviceId, manager.activeReconnectDeviceId)
                assertEquals(snapshot, manager.snapshot.value)
                gate.complete(Unit); runCurrent(); work.await()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, services.last().monitoringStarts)
            }
        },
        original("ConnectionManagerSessionTests", "handleReconnectionFailure clears state and sets disconnected") {
            withFixture {
                connect(); manager.handleReconnectionFailure()
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState); assertNull(manager.connectedDevice)
                assertTrue(manager.allowedRepeatFrequencyRanges.isEmpty()); assertNull(manager.snapshot.value.token)
            }
        },
        original("ConnectionManagerSessionTests", "checkWiFiConnectionHealth returns early when reconnect in progress") {
            withFixture {
                val wifi = ConnectionTarget.WiFi("localhost", 5000u); connect(wifi)
                val gate = CompletableDeferred<Unit>()
                var connectEntered = false
                createRadio = { TestRadio().also { it.beforeConnect = { connectEntered = true; gate.await() } } }
                links.first().callbacks!!.onDisconnected(IllegalStateException("lost")); runCurrent()
                assertTrue(connectEntered); assertEquals(1, radios.last().connectionAttempts)
                val count = radios.size; manager.checkWiFiConnectionHealth(); assertEquals(count, radios.size)
                gate.complete(Unit); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, services.last().monitoringStarts)
            }
        },
        original("ConnectionManagerSessionTests", "checkWiFiConnectionHealth returns early when disconnected without intent") {
            withFixture { manager.checkWiFiConnectionHealth(); assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState); assertTrue(radios.isEmpty()) }
        },
        original("ConnectionManagerSessionTests", "checkWiFiConnectionHealth returns early when transport is bluetooth") {
            withFixture { connect(); manager.checkWiFiConnectionHealth(); assertEquals(DeviceConnectionState.READY, manager.connectionState); assertEquals(1, radios.size) }
        },
        original("ConnectionManagerSessionTests", "handleReconnectionFailure notifies onConnectionLost so UI can react") {
            withFixture { connect(); manager.handleReconnectionFailure(); assertEquals(1, lossCount) }
        },
    )
}
