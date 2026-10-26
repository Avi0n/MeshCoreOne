// AndroidOnly: WP-207 Real-session generation, partial factory, cancellation and process-role lifecycle assertions.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeOwnershipTest {
    @TestFactory
    fun nativeCases() = listOf(
        nativeCase("service inputs bind the active BLE WiFi and replacement generation link types") {
            withFixture {
                devices.rows[platform.target.deviceId] = DeviceDTO(
                    id = platform.target.deviceId,
                    radioId = RadioId(UUID.randomUUID()),
                    publicKey = publicKey,
                    nodeName = "Dual method",
                    connectionMethods = listOf(
                        ConnectionMethod.Bluetooth(platform.target.deviceId),
                        ConnectionMethod.WiFi("localhost", 5000u),
                    ).snapshot(),
                )
                onFactory = { handle, _ ->
                    assertEquals(links.last().type, handle.inputs.transportType)
                }
                connect()
                val first = services.single()
                assertEquals(TransportType.BLUETOOTH, first.inputs.transportType)
                assertTrue(first.inputs.device.connectionMethods.any { it is ConnectionMethod.WiFi })
                manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                connect(ConnectionTarget.WiFi("localhost", 5000u))
                val second = services.last()
                assertEquals(TransportType.WIFI, second.inputs.transportType)
                assertTrue(second.token.generation.value > first.token.generation.value)
                assertEquals(1, first.teardowns)
                manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                connect()
                val third = services.last()
                assertEquals(TransportType.BLUETOOTH, third.inputs.transportType)
                assertTrue(third.token.generation.value > second.token.generation.value)
                assertEquals(1, second.teardowns)
                assertEquals(TransportType.BLUETOOTH, first.inputs.transportType, "old inputs retain their own link")
            }
        },
        nativeCase("first radio identity is raw SHA256 prefix without UUID version or variant rewriting") {
            withFixture {
                connect()
                // Python hashlib.sha256(bytes([0xc3])*32).digest()[:16], frozen independently.
                assertEquals("4B80290F-1D4D-74E6-61A0-EA719372CBA5", manager.connectedDevice!!.radioId.canonicalString)
                assertEquals(7, manager.connectedDevice!!.radioId.value.version())
                assertEquals(0, manager.connectedDevice!!.radioId.value.variant())
            }
        },
        nativeCase("restored arbitrary radio UUID survives new BLE endpoint and preserves process fields") {
            withFixture {
                val restored = RadioId(UUID.fromString("5C6AAB70-0737-4826-A3C2-C5305DECA821"))
                devices.rows[UUID.randomUUID()] = DeviceDTO(
                    radioId = restored, publicKey = publicKey, nodeName = "Restored",
                    lastContactSync = 0xf0000000u, knownRegions = listOf("regionA", "regionB").snapshot(), ocvPreset = "custom",
                )
                connect()
                assertEquals(restored, manager.connectedDevice!!.radioId)
                assertEquals(0xf0000000u, manager.connectedDevice!!.lastContactSync)
                assertEquals(listOf("regionA", "regionB"), manager.connectedDevice!!.knownRegions)
                assertEquals("custom", manager.connectedDevice!!.ocvPreset)
                assertEquals(restored, services.single().token.radioId)
            }
        },
        nativeCase("register callbacks and the sole ingestion reader before synchronous handshake replies") {
            withFixture {
                createRadio = { TestRadio().also { radio ->
                    radio.beforeSend = { assertEquals(1, links.last().registrations); assertEquals(1, radio.collectors) }
                } }
                connect()
                assertEquals(listOf(1, 0x16, 0x3b, 5), radios.single().frames.map { it[0].toInt() and 255 })
                assertEquals(1, radios.single().maximumCollectors)
            }
        },
        nativeCase("services available callback runs before monitors and initial sync and no early ready drain occurs") {
            withFixture {
                val states = mutableListOf<DeviceConnectionState>()
                val subscription = manager.subscribeTransitions()
                val collector = backgroundScope.launch { subscription.transitions.collect { states += it.state } }
                onAvailable = { assertEquals(0, services.single().monitoringStarts); assertEquals(DeviceConnectionState.CONNECTED, manager.connectionState) }
                onFactory = { handle, _ ->
                    handle.beforeSync = {
                        assertEquals(1, handle.monitoringStarts)
                        assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
                        assertFalse(manager.connectionState.canDrainSendQueue)
                    }
                }
                connect()
                assertEquals(listOf("hydrate", "start", "sync", "ensureListeners"), services.single().calls)
                assertEquals(DeviceConnectionState.READY, states.last())
                subscription.close(); collector.cancelAndJoin()
            }
        },
        nativeCase("a failed sync stays syncing with original issue and cannot drain sends") {
            withFixture {
                val failure = IllegalStateException("unusable contacts")
                onFactory = { handle, _ -> handle.syncResult = RuntimeSyncResult.Failed(failure) }
                manager.connect(platform.target)
                assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
                assertFalse(manager.connectionState.canDrainSendQueue)
                assertSame(failure, assertIs<ConnectionIssue.Lifecycle>(manager.snapshot.value.issue).cause)
                assertEquals(0, syncedCount); assertEquals(listOf(1, 0x16, 0x3b), radios.single().frames.map { it[0].toInt() and 255 })
            }
        },
        nativeCase("overlapping same-target connects create one session one service handle and one monitor") {
            withFixture {
                val gate = CompletableDeferred<Unit>(); createRadio = { TestRadio().also { it.beforeConnect = { gate.await() } } }
                val a = backgroundScope.async { manager.connect(platform.target) }; val b = backgroundScope.async { manager.connect(platform.target) }
                runCurrent(); assertEquals(1, radios.size); gate.complete(Unit); runCurrent(); a.await(); b.await()
                assertEquals(1, services.size); assertEquals(1, services.single().monitoringStarts); assertEquals(1, radios.single().maximumCollectors)
            }
        },
        nativeCase("cancelled coalesced observer cannot cancel the authoritative handshake or clear its pending slot") {
            withFixture {
                val gate = CompletableDeferred<Unit>()
                createRadio = { TestRadio().also { radio ->
                    radio.beforeSend = { frame -> if (frame[0].toInt() == 1) gate.await() }
                } }
                val owner = backgroundScope.async { manager.connect(platform.target) }
                runCurrent()
                val observer = backgroundScope.async { manager.connect(platform.target) }
                try {
                    runCurrent()
                    val beforeCancellation = manager.snapshot.value
                    assertEquals(DeviceConnectionState.CONNECTED, beforeCancellation.state)
                    assertNull(beforeCancellation.token); assertFalse(beforeCancellation.state.canDrainSendQueue)
                    observer.cancelAndJoin()
                    assertTrue(observer.isCancelled); assertTrue(owner.isActive)
                    assertEquals(platform.target.deviceId, manager.activeConnectionAttemptDeviceId)
                    assertEquals(beforeCancellation, manager.snapshot.value)
                    assertEquals(1, radios.size); assertEquals(0, radios.single().closes)
                    assertEquals(1, radios.single().collectors); assertTrue(services.isEmpty())
                    gate.complete(Unit); runCurrent(); owner.await()
                    assertEquals(DeviceConnectionState.READY, manager.connectionState)
                    assertEquals(services.single().token, manager.snapshot.value.token)
                    assertEquals(1, services.single().monitoringStarts); assertEquals(0, services.single().teardowns)
                    assertEquals(1, radios.single().maximumCollectors); assertEquals(0, radios.single().closes)
                    assertNull(manager.activeConnectionAttemptDeviceId)
                } finally {
                    gate.complete(Unit)
                    withContext(NonCancellable) { observer.cancelAndJoin(); owner.cancelAndJoin() }
                }
            }
        },
        nativeCase("different-target connect invalidates old handshake before acquiring a replacement") {
            withFixture {
                val gate = CompletableDeferred<Unit>(); createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 1) gate.await() } } }
                val a = backgroundScope.async { runCatching { manager.connect(platform.target) } }; runCurrent()
                createRadio = { TestRadio() }; val bTarget = target(); val b = backgroundScope.async { manager.connect(bTarget) }
                runCurrent(); b.await()
                assertIs<CancellationException>(a.await().exceptionOrNull())
                assertEquals(bTarget.deviceId, manager.connectedDevice?.id)
                assertEquals(1, radios.first().closes); assertEquals(0, radios.first().collectors)
                assertEquals(1, services.size); gate.complete(Unit)
            }
        },
        nativeCase("old physical callback cannot close or demote a newer same-device generation") {
            withFixture {
                connect(); val stale = links.first().callbacks!!; val oldToken = services.first().token
                manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP); connect()
                stale.onDisconnected(LinkFailure.AuthenticationFailed()); stale.onAutoReconnecting("stale"); stale.onReconnected(); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState); assertEquals(0, radios.last().closes)
                assertTrue(services.last().token.generation.value > oldToken.generation.value); assertTrue(authFailures.isEmpty())
                assertEquals(3, diagnostics.filterIsInstance<RuntimeDiagnostic.StaleCallback>().size)
            }
        },
        nativeCase("manual stop cancels handshake and closes exactly once without automatic recovery") {
            withFixture {
                val gate = CompletableDeferred<Unit>(); createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 1) gate.await() } } }
                val work = backgroundScope.async { runCatching { manager.connect(platform.target) } }; runCurrent()
                manager.disconnect(); runCurrent(); assertIs<CancellationException>(work.await().exceptionOrNull())
                assertEquals(1, radios.single().closes); assertEquals(0, radios.single().collectors)
                assertFalse(manager.isReconnectionWatchdogRunning); assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
                gate.complete(Unit)
            }
        },
        nativeCase("parent owning-job cancellation cannot prevent physical close or leak ingestion") {
            val parent = SupervisorJob(backgroundScope.coroutineContext[Job])
            val fixture = RuntimeFixture(this, parent)
            fixture.connect(); parent.cancel(); runCurrent()
            val report = fixture.manager.awaitShutdown()
            assertTrue(report.isComplete); assertEquals(1, fixture.radios.single().closes); assertEquals(0, fixture.radios.single().collectors)
            assertTrue(fixture.services.single().ownedJobs.all(Job::isCompleted)); assertEquals(1, fixture.services.single().teardowns)
            fixture.manager.close()
        },
        nativeCase("factory failure tears down registered partial handles and every allocated child") {
            withFixture {
                val cause = ConnectionError.InitializationFailed("partial graph"); var childClosures = 0
                onFactory = { _, ownership -> ownership.own(LifecycleStage.STOP_SERVICES) { childClosures++ }; throw cause }
                assertSame(cause, assertFailsWith<ConnectionError.InitializationFailed> { manager.connect(platform.target, false, true) })
                assertEquals(2, services.size); assertTrue(services.all { it.teardowns == 1 && it.callbacksCleared })
                assertEquals(2, childClosures); assertTrue(radios.all { it.closes == 1 && it.collectors == 0 })
            }
        },
        nativeCase("factory cancellation releases partial subscriptions and timers before returning") {
            withFixture {
                val gate = CompletableDeferred<Unit>(); var childClosures = 0
                onFactory = { handle, ownership ->
                    val listener = handle.inputs.connection.scope.launch { awaitCancellation() }
                    ownership.own(LifecycleStage.STOP_SERVICES) { listener.cancelAndJoin(); childClosures++ }
                    gate.await()
                }
                val work = backgroundScope.async { manager.connect(platform.target) }; runCurrent()
                work.cancelAndJoin(); runCurrent()
                assertEquals(1, childClosures); assertEquals(1, services.single().teardowns)
                assertEquals(1, radios.single().closes); assertEquals(0, radios.single().collectors)
                gate.complete(Unit)
            }
        },
        nativeCase("manual stop during monitor start closes starting graph instead of resurrecting active listeners") {
            withFixture {
                val gate = CompletableDeferred<Unit>(); onFactory = { handle, _ -> handle.beforeStart = { gate.await() } }
                val work = backgroundScope.async { runCatching { manager.connect(platform.target) } }; runCurrent()
                assertEquals(1, services.single().monitoringStarts); manager.disconnect(); runCurrent()
                assertIs<CancellationException>(work.await().exceptionOrNull())
                assertEquals(1, services.single().teardowns); assertTrue(services.single().ownedJobs.all(Job::isCompleted))
                gate.complete(Unit); runCurrent(); assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        nativeCase("cleanup error is reported while physical close and other owned cleanup still occur") {
            withFixture {
                val failure = IllegalStateException("RX flush failed")
                onFactory = { handle, _ -> handle.closeFailure = failure }
                connect(); val report = manager.disconnect()
                assertSame(failure, report.issues.single().cause); assertFalse(report.isComplete)
                assertEquals(1, radios.single().closes); assertEquals(0, radios.single().collectors)
                assertTrue(services.single().callbacksCleared)
            }
        },
        nativeCase("cleanup failures are suppressed onto original initialization failure instead of replacing it") {
            withFixture {
                val original = ConnectionError.InitializationFailed("construct"); val cleanup = IllegalStateException("flush")
                onFactory = { handle, _ -> handle.closeFailure = cleanup; throw original }
                val result = assertFailsWith<ConnectionError.InitializationFailed> { manager.connect(platform.target, false, true) }
                assertSame(original, result); assertTrue(result.suppressed.contains(cleanup))
                assertTrue(radios.all { it.closes == 1 })
            }
        },
        nativeCase("competing runtime cannot ingest or close a foreign physical session") {
            val shared = TestRadio(); val a = RuntimeFixture(this); val b = RuntimeFixture(this)
            a.createRadio = { shared }; b.createRadio = { shared }
            try {
                a.connect()
                assertFailsWith<ConnectionError.ForeignPhysicalOwner> { b.manager.connect(b.platform.target) }
                assertEquals(0, shared.closes); assertEquals(1, shared.collectors); assertEquals(1, shared.maximumCollectors)
                b.manager.close(); assertEquals(0, shared.closes)
            } finally { b.manager.close(); a.close() }
            assertEquals(1, shared.closes)
        },
        nativeCase("foreign raw session admission failure does not disconnect its owner") {
            withFixture {
                connect()
                val competitor = MeshCoreSession(radios.single(), coroutineContext = backgroundScope.coroutineContext)
                val failure = assertFailsWith<MeshCoreException.ConnectionLost> { competitor.start() }
                requiredCause<SessionCorrelationException.ConcurrentTransportOwner>(failure)
                competitor.stop()
                assertEquals(0, radios.single().closes); assertEquals(1, radios.single().collectors)
            }
        },
        nativeCase("retained physical receipt does not become a fresh logical generation by resubscribing") {
            withFixture {
                connect(); val predecessor = services.single().inputs.connection.session
                manager.teardownSessionForReconnect()
                val failure = assertFailsWith<MeshCoreException.ConnectionLost> { predecessor.start() }
                assertIs<SessionCorrelationException.RetainedTransport>(failure.cause)
                assertEquals(0, radios.single().closes); assertEquals(0, radios.single().collectors)
                manager.rebuildSession(platform.target.deviceId)
                assertEquals(1, radios.first().closes); assertEquals(1, radios.last().collectors)
            }
        },
        nativeCase("process repository and preferences stay usable across physical generations and runtime close") {
            withFixture {
                connect(); val first = manager.connectedDevice!!
                val raw = RuntimePreferenceValue.Text("unknown raw preference"); preferences.values["unrelated"] = raw
                manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP); connect(target())
                assertEquals(first.radioId, manager.connectedDevice!!.radioId)
                manager.close(); assertNotNull(devices.fetchDevice(manager.snapshot.value.token?.radioId ?: first.radioId))
                assertEquals(raw, preferences.read().values["unrelated"]); assertEquals(first.radioId, last.read().radioId)
            }
        },
        nativeCase("process launch resets all remote sessions once before platform activation or reauthentication") {
            withFixture {
                preferences.values[PersistenceKeys.USER_EXPLICITLY_DISCONNECTED] = RuntimePreferenceValue.Flag(true)
                manager.activate(); manager.activate()
                assertEquals(1, resetCount); assertEquals(1, platform.activationCount); assertEquals(listOf("global-reset"), order)
                assertTrue(radios.isEmpty()); assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
            }
        },
        nativeCase("bounded monitor start is single-flight across two concurrent callers and stop is exactly once") {
            val fixture = RuntimeFixture(this)
            try {
                fixture.connect()
                val inputs = fixture.services.single().inputs
                val handle = TestServices(inputs); val ownership = FactoryOwnership(handle.token)
                ownership.register(handle)
                val owned = OwnedRadioServices(handle, ownership, backgroundScope.coroutineContext)
                val gate = CompletableDeferred<Unit>(); handle.beforeStart = { gate.await() }
                val a = backgroundScope.async { owned.startMonitoring(MonitoringOptions()) }
                val b = backgroundScope.async { owned.startMonitoring(MonitoringOptions()) }; runCurrent()
                assertEquals(1, handle.monitoringStarts)
                val stop = backgroundScope.async { owned.close() }; runCurrent()
                gate.complete(Unit); runCurrent(); stop.await()
                assertTrue(a.isCancelled && b.isCancelled); assertEquals(1, handle.teardowns)
                owned.close(); assertEquals(1, handle.teardowns)
            } finally { fixture.close() }
        },
        nativeCase("retry delay rows and jitter boundaries are source exact") {
            assertEquals(listOf(300.milliseconds, 600.milliseconds, 1200.milliseconds, 2400.milliseconds),
                (1..4).map { ConnectionRetryPolicy.connectDelay(it, 0.0) })
            assertEquals(330.milliseconds, ConnectionRetryPolicy.connectDelay(1, 0.1))
            assertEquals(listOf(500.milliseconds, 1000.milliseconds, 2000.milliseconds, 4000.milliseconds, 4000.milliseconds),
                (1..5).map(ConnectionRetryPolicy::wifiDelay))
            assertEquals(listOf(30_000.milliseconds, 60_000.milliseconds, 120_000.milliseconds, 120_000.milliseconds),
                (1..4).map(ConnectionRetryPolicy::watchdogDelay))
        },
        nativeCase("pre-cancelled connect has no intent mutation or platform radio creation") {
            withFixture {
                val task = backgroundScope.launch(start = CoroutineStart.LAZY) { manager.connect(platform.target) }
                task.cancelAndJoin()
                assertTrue(radios.isEmpty()); assertEquals(ConnectionIntent.None, manager.connectionIntent)
                assertEquals(0, preferences.writes)
            }
        },
        nativeCase("storage fetch failure is typed and never invents a fresh radio identity") {
            withFixture {
                val error = PersistenceStoreException(PersistenceStoreError.FetchFailed("read"))
                devices.failure = error
                assertSame(error, assertFailsWith<PersistenceStoreException> { manager.connect(platform.target, false, true) })
                assertTrue(services.isEmpty()); assertTrue(devices.rows.isEmpty()); assertNull(manager.connectedDevice)
                assertTrue(radios.all { it.closes == 1 })
            }
        },
        nativeCase("invalid WiFi target fails before intent state callbacks or transport allocation") {
            withFixture {
                assertFailsWith<com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException> {
                    manager.connect(ConnectionTarget.WiFi("  ", 5000u))
                }
                assertTrue(radios.isEmpty()); assertEquals(ConnectionIntent.None, manager.connectionIntent)
            }
        },
        nativeCase("services-available callback can synchronously disconnect without a non-reentrant operation deadlock") {
            withFixture {
                onAvailable = { manager.disconnect() }
                assertFailsWith<CancellationException> { manager.connect(platform.target) }
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(1, radios.single().closes); assertEquals(1, services.single().teardowns)
                assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
            }
        },
        nativeCase("services-available callback can replace its own generation before stale ready promotion") {
            withFixture {
                val replacement = target()
                var replaced = false
                onAvailable = {
                    if (!replaced) { replaced = true; manager.connect(replacement) }
                }
                assertFailsWith<CancellationException> { manager.connect(platform.target) }
                assertEquals(replacement.deviceId, manager.connectedDevice?.id)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, radios.first().closes); assertEquals(0, radios.last().closes)
                assertEquals(0, services.first().monitoringStarts); assertEquals(1, services.last().monitoringStarts)
            }
        },
        nativeCase("forget during suspended bond-refresh query cannot resurrect the persisted shield") {
            withFixture {
                connect()
                val link = links.single()
                val gate = CompletableDeferred<Unit>(); link.beforeBondRefresh = { gate.await() }
                link.callbacks!!.onBondRefreshed(); runCurrent()
                manager.clearPersistedConnection(platform.target.deviceId)
                gate.complete(Unit); runCurrent()
                assertNull(last.bondVerificationDate(platform.target.deviceId))
                assertNull(last.read().deviceId); assertEquals(listOf(platform.target.deviceId), link.clearedBonds)
            }
        },
        nativeCase("uncertain physical close never releases its lease to a competing runtime") {
            val shared = TestRadio(); val a = RuntimeFixture(this); val b = RuntimeFixture(this)
            a.createRadio = { shared }; b.createRadio = { shared }
            try {
                a.connect(); shared.beforeClose = { throw IllegalStateException("close did not reach driver") }
                val report = a.manager.disconnect()
                assertFalse(report.isComplete); assertTrue(shared.isConnected())
                assertFailsWith<ConnectionError.ForeignPhysicalOwner> { b.manager.connect(b.platform.target) }
                assertEquals(0, shared.closes)
            } finally {
                shared.beforeClose = {}
                shared.disconnect()
                b.close(); a.close()
            }
        },
        nativeCase("optional BLE activation failure does not block the persisted WiFi connection") {
            withFixture {
                val id = UUID.randomUUID(); val radio = RadioId(UUID.randomUUID())
                devices.rows[id] = DeviceDTO(id = id, radioId = radio, publicKey = publicKey, nodeName = "WiFi",
                    connectionMethods = listOf(ConnectionMethod.WiFi("localhost", 5000u)).snapshot())
                last.persist(id, radio, "WiFi")
                platform.activationFailure = ConnectionError.UnsupportedCapability("companion association")
                manager.activate()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(TransportType.WIFI, links.single().type)
                assertTrue(diagnostics.filterIsInstance<RuntimeDiagnostic.Failure>().any { it.cause === platform.activationFailure })
                assertEquals(1, resetCount)
            }
        },
        nativeCase("synchronous callback during registration owns a session and closes a late registration without starting radio traffic") {
            val fixture = RuntimeFixture(this, dispatcher = UnconfinedTestDispatcher(testScheduler))
            try {
                fixture.onRegistration = { it.onDisconnected(LinkFailure.ConnectionFailed("registration loss")) }
                val work = backgroundScope.async { runCatching { fixture.manager.connect(fixture.platform.target) } }
                runCurrent(); assertIs<CancellationException>(work.await().exceptionOrNull())
                assertTrue(fixture.radios.single().frames.isEmpty()); assertEquals(0, fixture.radios.single().connects)
                assertEquals(0, fixture.radios.single().collectors)
                assertNull(fixture.manager.snapshot.value.token)
                fixture.manager.disconnect()
                assertEquals(1, fixture.links.single().callbackClosures)
            } finally { fixture.close() }
        },
        nativeCase("cancelled sync with delayed completion cannot publish a stale token or ready state") {
            withFixture {
                val gate = CompletableDeferred<Unit>()
                onFactory = { handle, _ -> handle.beforeSync = { withContext(NonCancellable) { gate.await() } } }
                val work = backgroundScope.async { runCatching { manager.connect(platform.target) } }; runCurrent()
                assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
                val stop = backgroundScope.async { manager.disconnect() }; runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState); assertNull(manager.snapshot.value.token)
                assertFalse(stop.isCompleted)
                gate.complete(Unit); runCurrent(); stop.await()
                assertIs<CancellationException>(work.await().exceptionOrNull())
                assertEquals(0, syncedCount); assertEquals(1, services.single().teardowns)
                assertEquals(1, radios.single().closes); assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        nativeCase("retired sync callbacks cannot overwrite a successor process cache or timestamps") {
            withFixture {
                connect(); val old = services.single().inputs.callbacks
                createRadio = { TestRadio().also { it.key = Bytes(ByteArray(32) { 7 }) } }
                manager.connect(target())
                val clean = manager.lastCleanChannelSync; val attempted = manager.lastAttemptedChannelSync
                advanceTimeBy(2000); old.cleanChannelSync(); old.channelSyncAttempted()
                assertEquals(clean, manager.lastCleanChannelSync); assertEquals(attempted, manager.lastAttemptedChannelSync)
                assertEquals(manager.connectedDevice!!.radioId, clean!!.first)
            }
        },
        nativeCase("foreground health query finishing after manual stop cannot recreate connection intent") {
            withFixture {
                connect(); manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                val gate = CompletableDeferred<Unit>()
                platform.onState = { gate.await() }
                val health = backgroundScope.async { manager.checkBLEConnectionHealth() }; runCurrent()
                manager.disconnect()
                gate.complete(Unit); runCurrent(); health.await()
                assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
                assertEquals(ConnectionIntent.UserDisconnected, last.restoredIntent())
                assertEquals(1, radios.size); assertFalse(manager.isReconnectionWatchdogRunning)
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
            }
        },
        nativeCase("old rebuild-failure query cannot notify loss or tear down a ready successor") {
            withFixture {
                connect()
                val gate = CompletableDeferred<Unit>(); var first = true
                platform.onState = { if (first) { first = false; gate.await() } }
                val failure = backgroundScope.async { manager.handleReconnectionFailure() }; runCurrent()
                val successor = target(); manager.connect(successor)
                gate.complete(Unit); runCurrent(); failure.await()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(successor.deviceId, manager.connectedDevice?.id)
                assertEquals(0, lossCount); assertEquals(0, radios.last().closes)
                assertEquals(1, radios.first().closes); assertEquals(0, manager.consecutiveRebuildFailures)
            }
        },
        nativeCase("temporary partial factory failure closes its generation and retries with one fresh service graph") {
            withFixture {
                var first = true; var partialClosed = 0
                onFactory = { _, ownership ->
                    if (first) {
                        first = false
                        ownership.own(LifecycleStage.STOP_SERVICES) { partialClosed++ }
                        throw ConnectionError.InitializationFailed("temporary construction failure")
                    }
                }
                manager.connect(platform.target, false, true)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(2, radios.size); assertEquals(1, radios.first().closes); assertEquals(0, radios.first().collectors)
                assertEquals(1, partialClosed); assertEquals(1, services.first().teardowns)
                assertEquals(0, services.first().monitoringStarts); assertEquals(1, services.last().monitoringStarts)
                assertEquals(1, radios.last().collectors); assertEquals(1, devices.rows.size)
            }
        },
        nativeCase("rapid duplicate reconnect completions rebuild one actual session and one monitor generation") {
            withFixture {
                connect()
                val callbacks = links.single().callbacks!!
                callbacks.onAutoReconnecting("dropped"); runCurrent()
                repeat(5) { callbacks.onReconnected() }; runCurrent()
                assertEquals(2, radios.size); assertEquals(2, services.size)
                assertEquals(1, radios.first().closes); assertEquals(0, radios.first().collectors)
                assertEquals(1, services.first().teardowns); assertEquals(1, services.last().monitoringStarts)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertTrue(radios.all { it.maximumCollectors == 1 })
            }
        },
        nativeCase("cancellation after ready during real time query retires token session and graph together") {
            withFixture {
                val gate = CompletableDeferred<Unit>()
                createRadio = { TestRadio().also { it.beforeSend = { frame -> if (frame[0].toInt() == 5) gate.await() } } }
                val work = backgroundScope.async { manager.connect(platform.target) }; runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                work.cancelAndJoin(); runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState); assertNull(manager.snapshot.value.token)
                assertNull(manager.connectedDevice); assertEquals(1, services.single().teardowns)
                assertEquals(1, radios.single().closes); assertEquals(0, radios.single().collectors)
                gate.complete(Unit)
            }
        },
        nativeCase("older disconnect finishing cleanup cannot close or persist manual-stop over a newer ready connection") {
            withFixture {
                connect()
                val gate = CompletableDeferred<Unit>(); var first = true
                preferences.beforeUpdate = { if (first) { first = false; gate.await() } }
                val oldStop = backgroundScope.async { manager.disconnect() }; runCurrent()
                val successor = target(); manager.connect(successor)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                gate.complete(Unit); runCurrent(); oldStop.await()
                assertEquals(successor.deviceId, manager.connectedDevice?.id); assertEquals(0, radios.last().closes)
                assertEquals(ConnectionIntent.WantsConnection(), manager.connectionIntent)
                assertEquals(ConnectionIntent.None, last.restoredIntent())
                assertEquals(1, radios.first().closes)
            }
        },
        nativeCase("stored BLE restoration installs its declared callback route before platform activation") {
            withFixture {
                val radio = RadioId(UUID.randomUUID())
                last.persist(platform.target.deviceId, radio, "Stored")
                platform.state = platform.state.copy(autoReconnecting = true, connectedDeviceId = platform.target.deviceId)
                platform.onActivate = {
                    assertEquals(1, links.size); assertEquals(1, links.single().registrations)
                    links.single().callbacks!!.onAutoReconnecting("restored")
                }
                manager.activate(); runCurrent()
                assertTrue(manager.isTransportAutoReconnecting())
                links.first().callbacks!!.onReconnected(); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, services.size); assertEquals(1, services.single().monitoringStarts)
                assertEquals(1, radios.last().collectors)
            }
        },
        nativeCase("owned system adoption reaches ready through the actual registered link route rather than a manager backdoor") {
            withFixture {
                last.persist(platform.target.deviceId, RadioId(UUID.randomUUID()), "Stored")
                platform.state = platform.state.copy(systemConnected = true)
                platform.adoptionSucceeds = true
                manager.connect(platform.target, true, true)
                assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
                assertEquals(1, links.size); assertEquals(0, radios.single().collectors)
                links.single().callbacks!!.onReconnected(); runCurrent()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, services.size); assertEquals(listOf(true), services.single().syncForces)
            }
        },
        nativeCase("remote reauthentication IDs never cross distinct radio partitions with the same session UUID") {
            withFixture {
                val sameSession = UUID.randomUUID()
                onFactory = { handle, _ -> handle.remoteSessions = setOf(sameSession) }
                connect(); links.first().callbacks!!.onAutoReconnecting("lost"); runCurrent()
                createRadio = { TestRadio().also { it.key = Bytes(ByteArray(32) { 17 }) } }
                manager.connect(target())
                assertTrue(services.last().reauthenticated.isEmpty())
                assertNotEquals(services.first().token.radioId, services.last().token.radioId)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
            }
        },
        nativeCase("authentication failure before connected callback surfaces once and a healthy episode resets the latch") {
            withFixture {
                createRadio = { TestRadio().also { it.connectFailure = LinkFailure.AuthenticationFailed() } }
                repeat(2) { assertFailsWith<LinkFailure.AuthenticationFailed> { manager.connect(platform.target, false, true) } }
                assertEquals(listOf(platform.target.deviceId), authFailures); assertTrue(manager.shouldAllowConnection(false))
                createRadio = { TestRadio() }; connect(); manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                createRadio = { TestRadio().also { it.connectFailure = LinkFailure.AuthenticationFailed() } }
                assertFailsWith<LinkFailure.AuthenticationFailed> { manager.connect(platform.target, false, true) }
                assertEquals(listOf(platform.target.deviceId, platform.target.deviceId), authFailures)
            }
        },
        nativeCase("queued health rebuild from radio A cannot adopt the newer manual radio B revision") {
            withFixture {
                connect()
                platform.state = platform.state.copy(connected = true)
                manager.handleReconnectionFailure()
                val field = manager.javaClass.getDeclaredField("operations").apply { isAccessible = true }
                val mutex = assertIs<kotlinx.coroutines.sync.Mutex>(field.get(manager))
                mutex.lock()
                val health = backgroundScope.async { runCatching { manager.checkBLEConnectionHealth() } }; runCurrent()
                val successor = target()
                val manual = backgroundScope.async { manager.connect(successor) }; runCurrent()
                mutex.unlock(); runCurrent()
                assertIs<CancellationException>(health.await().exceptionOrNull()); manual.await()
                assertEquals(successor.deviceId, manager.connectedDevice?.id)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(1, radios.first().closes); assertEquals(0, radios.last().closes)
                assertEquals(1, radios.last().collectors)
            }
        },
        nativeCase("superseding a never-executed connect still drains its independent detached-owner stop receipt") {
            withFixture {
                connect()
                val field = manager.javaClass.getDeclaredField("operations").apply { isAccessible = true }
                val mutex = assertIs<kotlinx.coroutines.sync.Mutex>(field.get(manager))
                mutex.lock()
                val b = backgroundScope.async { runCatching { manager.connect(target()) } }; runCurrent()
                val cTarget = target()
                val c = backgroundScope.async { manager.connect(cTarget) }; runCurrent()
                mutex.unlock(); runCurrent(); c.await()
                assertIs<CancellationException>(b.await().exceptionOrNull())
                assertEquals(cTarget.deviceId, manager.connectedDevice?.id)
                assertEquals(2, radios.size); assertEquals(1, radios.first().closes)
                assertEquals(0, radios.first().collectors); assertEquals(1, services.first().teardowns)
                assertEquals(1, radios.last().collectors)
            }
        },
        nativeCase("health query before atomic claim cannot borrow a completed successor's authority") {
            withFixture {
                connect(); platform.state = platform.state.copy(connected = true)
                manager.handleReconnectionFailure()
                val gate = CompletableDeferred<Unit>(); var first = true
                platform.onState = { if (first) { first = false; gate.await() } }
                val health = backgroundScope.async { manager.checkBLEConnectionHealth() }; runCurrent()
                val successor = target(); manager.connect(successor)
                gate.complete(Unit); runCurrent(); health.await()
                assertEquals(successor.deviceId, manager.connectedDevice?.id)
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(0, radios.last().closes); assertEquals(1, radios.last().collectors)
                assertEquals(2, services.size)
            }
        },
        nativeCase("process close drains detached disconnect receipts before cancelling the cleanup owner") {
            withFixture {
                connect()
                val gate = CompletableDeferred<Unit>(); services.first().beforeTeardown = { gate.await() }
                val disconnect = backgroundScope.async { manager.disconnect() }; runCurrent()
                val close = backgroundScope.async { manager.close() }; runCurrent()
                assertFalse(close.isCompleted); assertFalse(disconnect.isCompleted); assertEquals(0, radios.first().closes)
                gate.complete(Unit); runCurrent()
                assertTrue(disconnect.await().isComplete); assertTrue(close.await().isComplete)
                assertEquals(1, radios.first().closes); assertEquals(0, radios.first().collectors)
                assertTrue(services.first().ownedJobs.all(Job::isCompleted))
            }
        },
        nativeCase("proven foreign protocol admission refusal releases only the tentative runtime reservation") {
            val shared = TestRadio()
            val foreign = MeshCoreSession(shared,
                com.meshcoreone.android.core.protocol.config.SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
                coroutineContext = backgroundScope.coroutineContext)
            val failed = RuntimeFixture(this); val fresh = RuntimeFixture(this)
            failed.createRadio = { shared }; fresh.createRadio = { shared }
            try {
                foreign.start()
                val error = assertFailsWith<MeshCoreException.ConnectionLost> { failed.manager.connect(failed.platform.target) }
                requiredCause<SessionCorrelationException.ConcurrentTransportOwner>(error)
                assertEquals(0, shared.closes); assertEquals(1, shared.collectors)
                failed.manager.close(); foreign.stop()
                fresh.connect(); assertEquals(1, shared.collectors); assertEquals(DeviceConnectionState.READY, fresh.manager.connectionState)
            } finally {
                foreign.stop(); fresh.close(); failed.close()
            }
        },
        nativeCase("accepted manual lazy job prevents multithread health from borrowing its revision before start") {
            withFixture {
                connect(); platform.state = platform.state.copy(connected = true)
                manager.handleReconnectionFailure()
                val accepted = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                onSubmission = {
                    accepted.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                }
                val successor = target()
                val manual = backgroundScope.async(Dispatchers.Default) { manager.connect(successor) }
                try {
                    assertTrue(accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val before = radios.size
                    manager.checkBLEConnectionHealth()
                    assertEquals(before, radios.size)
                    release.countDown()
                    runCurrent(); manual.await()
                    assertEquals(successor.deviceId, manager.connectedDevice?.id)
                    assertEquals(2, radios.size); assertEquals(1, radios.first().closes)
                    assertEquals(1, radios.last().collectors)
                } finally { release.countDown(); withContext(NonCancellable) { manual.cancelAndJoin() } }
            }
        },
        nativeCase("inherited typed teardown failure survives later switches and remains visible at process close") {
            withFixture {
                connect()
                val issue = IllegalStateException("radio A RX flush")
                services.first().closeFailure = issue
                manager.connect(target())
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                manager.connect(target())
                assertTrue(diagnostics.filterIsInstance<RuntimeDiagnostic.Teardown>().any { report ->
                    report.report.issues.any { it.cause === issue }
                })
                val closed = manager.close()
                assertFalse(closed.isComplete); assertTrue(closed.issues.any { it.cause === issue })
                assertTrue(radios.all { it.closes == 1 && it.collectors == 0 })
            }
        },
        nativeCase("proven foreign retained-link refusal never leaves a phantom tentative runtime owner") {
            val shared = TestRadio()
            val foreign = MeshCoreSession(shared,
                com.meshcoreone.android.core.protocol.config.SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
                coroutineContext = backgroundScope.coroutineContext)
            val failed = RuntimeFixture(this); val fresh = RuntimeFixture(this)
            failed.createRadio = { shared }; fresh.createRadio = { shared }
            try {
                foreign.start(); foreign.stop(disconnectTransport = false)
                val error = assertFailsWith<MeshCoreException.ConnectionLost> { failed.manager.connect(failed.platform.target) }
                requiredCause<SessionCorrelationException.RetainedTransport>(error)
                assertEquals(0, shared.closes); assertTrue(shared.isConnected())
                failed.manager.close(); assertEquals(0, shared.closes)
                foreign.stop(disconnectTransport = true)
                fresh.connect(); assertEquals(1, shared.collectors); assertEquals(DeviceConnectionState.READY, fresh.manager.connectionState)
            } finally { foreign.stop(); fresh.close(); failed.close() }
        },
        nativeCase("throwing submission diagnostic drains its new stop receipt and releases the accepted slot for recovery") {
            withFixture {
                connect()
                val failure = ConnectionError.InitializationFailed("diagnostic observer")
                onSubmission = { throw failure }
                assertSame(failure, assertFailsWith<ConnectionError.InitializationFailed> { manager.connect(target()) })
                assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
                assertEquals(1, radios.first().closes); assertEquals(0, radios.first().collectors)
                assertEquals(1, services.first().teardowns); assertNull(manager.activeConnectionAttemptDeviceId)
                onSubmission = {}
                manager.checkBLEConnectionHealth()
                assertEquals(DeviceConnectionState.READY, manager.connectionState)
                assertEquals(2, radios.size); assertEquals(1, radios.last().collectors)
            }
        },
        nativeCase("equivalent callers coalesce an accepted lazy submission before its first reporter returns") {
            withFixture {
                val accepted = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                onSubmission = { accepted.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                val first = backgroundScope.async(Dispatchers.Default) { manager.connect(platform.target) }
                try {
                    assertTrue(accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    manager.connect(platform.target)
                    assertEquals(1, services.size); assertEquals(1, services.single().monitoringStarts)
                    release.countDown(); first.await()
                    assertEquals(1, diagnostics.filterIsInstance<RuntimeDiagnostic.OperationSubmitted>().size)
                    assertEquals(1, radios.size); assertEquals(1, radios.single().maximumCollectors)
                } finally { release.countDown(); withContext(NonCancellable) { first.cancelAndJoin() } }
            }
        },
        nativeCase("late owner reporter failure preserves the ready generation completed by its paused coalescer") {
            withFixture {
                val accepted = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                val failure = ConnectionError.InitializationFailed("late submission diagnostic")
                onSubmission = {
                    accepted.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    throw failure
                }
                val continuations = ArrayDeque<Runnable>()
                val paused = object : CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                        continuations.addLast(block)
                    }
                }
                val owner = backgroundScope.async(Dispatchers.Default) { runCatching { manager.connect(platform.target) } }
                var coalescer: Deferred<Unit>? = null
                try {
                    assertTrue(accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val observer = backgroundScope.async(paused) { manager.connect(platform.target) }
                    coalescer = observer
                    continuations.removeFirst().run()
                    runCurrent()
                    assertEquals(1, continuations.size); assertFalse(observer.isCompleted)
                    assertEquals(DeviceConnectionState.READY, manager.connectionState)
                    val ready = manager.snapshot.value
                    assertEquals(services.single().token, ready.token)
                    assertEquals(platform.target.deviceId, manager.activeConnectionAttemptDeviceId)
                    release.countDown()
                    assertSame(failure, owner.await().exceptionOrNull())
                    assertEquals(ready, manager.snapshot.value)
                    assertEquals(0, services.single().teardowns); assertEquals(0, radios.single().closes)
                    assertEquals(1, radios.single().collectors); assertNull(manager.activeConnectionAttemptDeviceId)
                    continuations.removeFirst().run(); observer.await()
                    assertEquals(1, radios.size); assertEquals(1, services.size)
                    assertEquals(1, services.single().monitoringStarts)
                    assertEquals(1, diagnostics.filterIsInstance<RuntimeDiagnostic.OperationSubmitted>().size)
                } finally {
                    release.countDown()
                    withContext(NonCancellable) {
                        owner.cancelAndJoin()
                        coalescer?.cancel()
                        while (continuations.isNotEmpty()) continuations.removeFirst().run()
                        coalescer?.join()
                    }
                }
            }
        },
        nativeCase("guarded diagnostic reentry starts the same submitted operation without duplicate generation or event") {
            val fixture = RuntimeFixture(this, dispatcher = UnconfinedTestDispatcher(testScheduler))
            try {
                var entered = false
                fixture.onSubmission = {
                    if (!entered) {
                        entered = true
                        runBlocking { fixture.manager.connect(fixture.platform.target) }
                    }
                }
                fixture.manager.connect(fixture.platform.target)
                assertEquals(DeviceConnectionState.READY, fixture.manager.connectionState)
                assertEquals(1, fixture.radios.size); assertEquals(1, fixture.services.size)
                assertEquals(1, fixture.diagnostics.filterIsInstance<RuntimeDiagnostic.OperationSubmitted>().size)
                assertEquals(1, fixture.radios.single().maximumCollectors)
            } finally { fixture.close() }
        },
    )
}
