// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerReconnectAbandonmentTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerPromotionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerSessionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ReconnectRebuildLifecycleTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.TransportType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectInterleavingTest {
    @TestFactory
    fun interleavings() = listOf(
        nativeCase("an auto-reconnect entry during a suspended failure query retains its physical recovery link") {
            withFixture {
                connect()
                val callbacks = links.single().callbacks!!
                platform.state = platform.state.copy(autoReconnecting = true, connectedDeviceId = platform.target.deviceId)
                val release = CompletableDeferred<Unit>()
                var queryEntered = false
                platform.onState = {
                    if (!queryEntered) { queryEntered = true; release.await() }
                }
                val failure = backgroundScope.async { manager.handleReconnectionFailure() }
                runCurrent()
                assertTrue(queryEntered)
                assertFalse(failure.isCompleted)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                callbacks.onAutoReconnecting("recovery claimed during query")
                runCurrent()
                assertEquals(platform.target.deviceId, manager.activeReconnectDeviceId)
                assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
                assertEquals(1, autoCount)
                release.complete(Unit)
                runCurrent()
                failure.await()
                assertEquals(0, radios.single().closes)
                assertEquals(0, links.single().callbackClosures)
                assertNotNull(links.single().callbacks)
                assertEquals(1, services.single().teardowns)
                assertEquals(0, lossCount)
                assertFalse(manager.isReconnectionWatchdogRunning)
                callbacks.onReconnected()
                runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(2, radios.size)
                assertEquals(1, radios.first().closes)
                assertEquals(1, services.last().monitoringStarts)
            }
        },
        nativeCase("a loss observer resuming after a newer failed connect cannot rearm the old watchdog") {
            withFixture {
                connect()
                val release = CompletableDeferred<Unit>()
                var observerEntered = false
                onLost = { observerEntered = true; release.await() }
                val failure = backgroundScope.async { manager.handleReconnectionFailure() }
                runCurrent()
                assertTrue(observerEntered)
                assertFalse(failure.isCompleted)
                assertEquals(1, lossCount)
                assertFalse(manager.isReconnectionWatchdogRunning)
                createRadio = { TestRadio().also { it.connectFailure = LinkFailure.AuthenticationFailed() } }
                val successor = target()
                assertFailsWith<LinkFailure.AuthenticationFailed> { manager.connect(successor, false, true) }
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertTrue(manager.connectionIntent.wantsConnection)
                assertEquals(2, radios.size)
                assertEquals(1, radios.last().connectionAttempts)
                assertFalse(manager.isReconnectionWatchdogRunning)
                release.complete(Unit)
                runCurrent()
                failure.await()
                assertFalse(manager.isReconnectionWatchdogRunning)
                assertEquals(listOf(successor.deviceId), authFailures)
                assertEquals(1, lossCount)
            }
        },
        nativeCase("reconnection failure suspended in teardown cannot notify loss for a manual successor") {
            withFixture {
                connect()
                platform.state = platform.state.copy(connected = true)
                val release = CompletableDeferred<Unit>()
                var teardownEntered = false
                services.single().beforeTeardown = { teardownEntered = true; release.await() }
                val failure = backgroundScope.async { manager.handleReconnectionFailure() }
                runCurrent()
                assertTrue(teardownEntered)
                assertFalse(failure.isCompleted)
                assertEquals(0, lossCount)
                val successor = target()
                val connection = backgroundScope.async { manager.connect(successor) }
                runCurrent()
                assertEquals(successor.deviceId, manager.activeConnectionAttemptDeviceId)
                assertFalse(connection.isCompleted)
                release.complete(Unit)
                runCurrent()
                failure.await()
                connection.await()
                assertEquals(0, lossCount)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(successor.deviceId, manager.connectedDevice?.id)
                assertEquals(1, radios.first().closes)
                assertEquals(0, radios.last().closes)
                assertFalse(manager.isReconnectionWatchdogRunning)
            }
        },
        nativeCase("user stop while reconnection failure tears down a held link prevents late loss notification") {
            withFixture {
                connect()
                platform.state = platform.state.copy(connected = true)
                val release = CompletableDeferred<Unit>()
                var teardownEntered = false
                services.single().beforeTeardown = { teardownEntered = true; release.await() }
                val failure = backgroundScope.async { manager.handleReconnectionFailure() }
                runCurrent()
                assertTrue(teardownEntered)
                assertTrue(radios.single().isConnected())
                assertEquals(0, radios.single().closes)
                val stop = backgroundScope.async { manager.disconnect() }
                runCurrent()
                assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                assertFalse(stop.isCompleted)
                release.complete(Unit)
                runCurrent()
                failure.await()
                assertTrue(stop.await().isComplete)
                assertEquals(0, lossCount)
                assertEquals(1, radios.single().closes)
                assertEquals(0, radios.single().collectors)
                assertFalse(manager.isReconnectionWatchdogRunning)
            }
        },
        nativeCase("exhausted preserve diagnostic finishing after a successor cannot publish another loss") {
            withFixture {
                connect()
                platform.state = platform.state.copy(connected = true)
                repeat(3) { manager.handleReconnectionFailure() }
                assertEquals(3, manager.consecutiveRebuildFailures)
                assertEquals(0, radios.single().closes)
                val release = CompletableDeferred<Unit>()
                var diagnosticEntered = false
                preferences.beforeUpdate = {
                    if (!diagnosticEntered) { diagnosticEntered = true; release.await() }
                }
                val failure = backgroundScope.async { manager.handleReconnectionFailure() }
                runCurrent()
                assertTrue(diagnosticEntered)
                assertFalse(failure.isCompleted)
                assertEquals(4, manager.consecutiveRebuildFailures)
                assertEquals(1, radios.single().closes)
                val lossesBefore = lossCount
                val successor = target()
                manager.connect(successor)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                release.complete(Unit)
                runCurrent()
                failure.await()
                assertEquals(lossesBefore, lossCount)
                assertEquals(successor.deviceId, manager.connectedDevice?.id)
                assertEquals(0, radios.last().closes)
                assertFalse(manager.isReconnectionWatchdogRunning)
            }
        },
        nativeCase("successful promotion waits for the real initial sync and emits one synced callback") {
            withFixture {
                val release = CompletableDeferred<Unit>()
                var syncEntered = false
                onFactory = { service, _ -> service.beforeSync = { syncEntered = true; release.await() } }
                val connection = backgroundScope.async { manager.connect(platform.target) }
                runCurrent()
                assertTrue(syncEntered)
                assertFalse(connection.isCompleted)
                assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
                assertEquals(0, syncedCount)
                val service = services.single()
                assertEquals(1, service.monitoringStarts)
                assertFalse("ensureListeners" in service.calls)
                release.complete(Unit)
                runCurrent()
                connection.await()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(service.token, manager.snapshot.value.token)
                assertEquals(TransportType.BLUETOOTH, links.single().type)
                assertEquals(1, syncedCount)
                assertEquals(1, service.calls.count { it == "ensureListeners" })
            }
        },
        nativeCase("a real failed sync remains syncing with its typed issue and never emits device synced") {
            withFixture {
                val release = CompletableDeferred<Unit>()
                val syncFailure = ConnectionError.InitializationFailed("channel baseline failed")
                var syncEntered = false
                onFactory = { service, _ ->
                    service.syncResult = RuntimeSyncResult.Failed(syncFailure)
                    service.beforeSync = { syncEntered = true; release.await() }
                }
                val connection = backgroundScope.async { manager.connect(platform.target) }
                runCurrent()
                assertTrue(syncEntered)
                assertFalse(connection.isCompleted)
                assertEquals(0, syncedCount)
                release.complete(Unit)
                runCurrent()
                connection.await()
                assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
                assertNotNull(manager.snapshot.value.issue)
                assertEquals(0, syncedCount)
                assertFalse("ensureListeners" in services.single().calls)
                assertTrue(diagnostics.any {
                    it is RuntimeDiagnostic.Failure && it.operation == "initialSync" && it.cause === syncFailure
                })
                assertEquals(1, radios.single().collectors)
            }
        },
        nativeCase("retiring the generation inside sync suppresses its ready token and synced callback") {
            withFixture {
                var syncEntered = false
                onFactory = { service, _ -> service.beforeSync = {
                    syncEntered = true
                    assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
                    manager.teardownSessionForReconnect()
                } }
                assertFailsWith<CancellationException> { manager.connect(platform.target) }
                runCurrent()
                assertTrue(syncEntered)
                assertNull(manager.snapshot.value.token)
                assertEquals(0, syncedCount)
                assertFalse("ensureListeners" in services.single().calls)
                assertEquals(1, services.single().teardowns)
                assertEquals(0, radios.single().collectors)
                assertEquals(0, radios.single().closes)
                assertFalse(manager.connectionState.isOperational)
            }
        },
        nativeCase("a second WiFi health check cannot open another link while reconnect connect is suspended") {
            withFixture {
                connect(ConnectionTarget.WiFi("localhost", 5000u))
                val token = services.single().token
                val release = CompletableDeferred<Unit>()
                var connectEntered = false
                createRadio = { TestRadio().also { it.beforeConnect = { connectEntered = true; release.await() } } }
                links.single().callbacks!!.onDisconnected(LinkFailure.ConnectionFailed("link lost"))
                runCurrent()
                assertTrue(connectEntered)
                assertEquals(2, radios.size)
                assertEquals(1, radios.last().connectionAttempts)
                assertNull(manager.snapshot.value.token)
                manager.checkWiFiConnectionHealth()
                runCurrent()
                assertEquals(2, radios.size)
                release.complete(Unit)
                runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertNotEquals(token.generation, manager.snapshot.value.token?.generation)
                assertEquals(1, services.last().monitoringStarts)
            }
        },
        nativeCase("fresh rebuild waits for predecessor close and rejects callbacks from its retired stream") {
            withFixture {
                connect()
                val oldToken = services.single().token
                val callbacks = links.single().callbacks!!
                manager.teardownSessionForReconnect()
                val release = CompletableDeferred<Unit>()
                var closeEntered = false
                radios.single().beforeClose = { closeEntered = true; release.await() }
                val rebuild = backgroundScope.async { manager.rebuildSession(platform.target.deviceId) }
                runCurrent()
                assertTrue(closeEntered)
                assertFalse(rebuild.isCompleted)
                assertEquals(1, radios.size)
                assertEquals(0, radios.single().collectors)
                release.complete(Unit)
                runCurrent()
                rebuild.await()
                val current = manager.snapshot.value
                assertEquals(DeviceConnectionState.READY, current.state)
                assertEquals(oldToken.radioId, current.token?.radioId)
                assertNotEquals(oldToken.generation, current.token?.generation)
                assertEquals(1, radios.first().closes)
                callbacks.onReconnected()
                callbacks.onDisconnected(LinkFailure.AuthenticationFailed())
                callbacks.onAutoReconnecting("retired predecessor")
                runCurrent()
                assertEquals(current, manager.snapshot.value)
                assertEquals(2, radios.size)
                assertEquals(0, lossCount)
                assertEquals(0, autoCount)
                assertTrue(authFailures.isEmpty())
                assertEquals(1, services.last().monitoringStarts)
            }
        },
    )
}
