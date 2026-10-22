// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+Lifecycle.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager+BLEReconnectionDelegate.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.protocol.session.SessionDiagnostic
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConnectionManager(
    private val devices: DevicePersisting,
    private val rooms: RoomPersisting,
    private val contacts: ContactPersisting,
    private val maintenance: ProcessRuntimeMaintenance,
    private val lastConnection: LastConnectionStore,
    private val platform: ConnectionPlatform,
    private val linkFactory: RuntimeLinkFactory,
    private val serviceFactory: RuntimeServiceFactory,
    private val observer: ConnectionObserver,
    private val reporter: RuntimeIssueReporter,
    private val clock: RuntimeClock,
    private val suspendingClock: DeadlineClock = clock,
    private val context: CoroutineContext = Dispatchers.Default,
    private val configuration: SessionConfiguration = SessionConfiguration.DEFAULT,
    private val jitter: () -> Double = { kotlin.random.Random.nextDouble(0.0, 0.1) },
    epoch: ProcessEpoch = ProcessEpoch(UUID.randomUUID()),
) : ConnectionController, BLEReconnectionDelegate {
    private class RuntimeOperation(val owner: Any) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<RuntimeOperation>
    }
    private data class Submission(
        val work: Deferred<Unit>,
        val previous: Deferred<Unit>?,
        val revision: Long,
        val ownsAttempt: Boolean,
        val stop: Deferred<TeardownReport>?,
        val detachedLive: Boolean = false,
    )
    internal class RadioGeneration(
        val number: Long,
        val target: ConnectionTarget,
        val revision: Long,
        context: CoroutineContext,
        parent: Job,
    ) {
        val lock = Any()
        val physicalOwner = UUID.randomUUID()
        val job = SupervisorJob(parent)
        val scope = CoroutineScope(context + job)
        var link: RuntimeLink? = null
        var ownsPhysical = false
        var callbacks: AutoCloseable? = null
        var session: MeshCoreSession? = null
        var token: SessionToken? = null
        var device: DeviceDTO? = null
        var ownership: FactoryOwnership? = null
        var services: OwnedRadioServices? = null
        var servicesClosed: Deferred<TeardownReport>? = null
        var physicalClosed: Deferred<TeardownReport>? = null
        var operation: Deferred<Unit>? = null
        var sawConnected = false
        var logicalEnded = false
        var physicalEnding = false
        var restorationRoute = false
        val registrationReady = CompletableDeferred<Unit>()
    }

    private val lock = Any()
    private val processJob = SupervisorJob(context[Job])
    private val processScope = CoroutineScope(context + processJob)
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(context.minusKey(Job) + cleanupJob)
    private val operations = Mutex()
    private val operationIdentity = Any()
    private val activation = Mutex()
    private val bondPersistence = Mutex()
    private val transitions = EventBroadcaster<ConnectionSnapshot>()
    private val values = MutableStateFlow(ConnectionSnapshot(
        DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null,
        ConnectionIntent.None, null, null,
    ))
    override val snapshot: StateFlow<ConnectionSnapshot> = values.asStateFlow()
    override val connectionIntent: ConnectionIntent get() = values.value.intent
    override val connectionState: DeviceConnectionState get() = values.value.state
    val processEpoch = epoch
    private var nextGeneration = 0L
    private var revision = 0L
    private var active: RadioGeneration? = null
    private var retained: RadioGeneration? = null
    private var pending: Deferred<Unit>? = null
    private var pendingTarget: ConnectionTarget? = null
    private var precedingStop: Deferred<TeardownReport>? = null
    private var activated = false
    private var closed = false
    private var shutdown: Deferred<TeardownReport>? = null
    private var pairing = false
    private var pairingFlow = false
    private var rebuildDevice: UUID? = null
    private var watchdog: Job? = null
    private var watchdogGeneration = 0L
    private var wifiReconnect: Job? = null
    private var lastWiFiReconnect: Duration? = null
    private var heartbeat: Job? = null
    private var foreground = true
    private var authFailureDevice: UUID? = null
    private var bondPersistEpoch = 0L
    private val awaitingReauth = mutableMapOf<RadioId, MutableSet<UUID>>()
    private val breaker = ConnectionCircuitBreaker(clock)
    private var rebuildFailures = 0
    private var deviceValue: DeviceDTO? = null
    private var repeatRanges: SnapshotList<FrequencyRange> = SnapshotList.empty()
    private var platformValue = DevicePlatform.UNKNOWN
    private var cleanSync: Pair<RadioId, Instant>? = null
    private var attemptedSync: Pair<RadioId, Instant>? = null
    val connectedDevice: DeviceDTO? get() = synchronized(lock) { deviceValue }
    val allowedRepeatFrequencyRanges: SnapshotList<FrequencyRange> get() = synchronized(lock) { repeatRanges }
    val detectedPlatform: DevicePlatform get() = synchronized(lock) { platformValue }
    val lastCleanChannelSync: Pair<RadioId, Instant>? get() = synchronized(lock) { cleanSync }
    val lastAttemptedChannelSync: Pair<RadioId, Instant>? get() = synchronized(lock) { attemptedSync }
    val consecutiveRebuildFailures: Int get() = synchronized(lock) { rebuildFailures }
    val isReconnectionWatchdogRunning: Boolean get() = synchronized(lock) { watchdog?.isActive == true }
    val reconnectionWatchdogGeneration: Long get() = synchronized(lock) { watchdogGeneration }
    val shouldDeferOpportunisticReconnect: Boolean get() = synchronized(lock) { pairing || pairingFlow }
    val activeConnectionAttemptDeviceId: UUID?
        get() = synchronized(lock) { (pendingTarget as? ConnectionTarget.Bluetooth)?.deviceId ?: rebuildDevice }
            ?: reconnectionCoordinator.reconnectingDeviceId
    val activeReconnectDeviceId: UUID? get() = synchronized(lock) { rebuildDevice }
        ?: reconnectionCoordinator.reconnectingDeviceId
    val reconnectionCoordinator = BLEReconnectionCoordinator(this, processScope, clock, reporter)

    init {
        processJob.invokeOnCompletion { cause ->
            if (cause != null) startShutdown(cause)
        }
    }

    override suspend fun subscribeTransitions(): ConnectionSubscription = synchronized(lock) {
        val subscription = transitions.subscribe()
        val initial = values.value
        object : ConnectionSubscription {
            override val initial = initial
            override val transitions = subscription.events
            override fun close() = subscription.close()
        }
    }

    suspend fun activate() = activation.withLock {
        if (activated) return@withLock
        requireOpen()
        // Native v1 already has stable IDs and sort dates: do not run Apple's destructive backfills.
        rooms.resetAllRemoteNodeSessionConnections()
        setIntent(lastConnection.restoredIntent())
        val last = lastConnection.read()
        val id = last.deviceId
        val device = id?.let { devices.fetchDevice(it) }
        val wifi = device?.connectionMethods?.filterIsInstance<ConnectionMethod.WiFi>()?.firstOrNull()
        var target: ConnectionTarget? = null
        if (connectionIntent != ConnectionIntent.UserDisconnected && id != null) {
            setIntent(ConnectionIntent.WantsConnection())
            if (wifi == null) {
                target = platform.targetForDevice(id)
                if (target != null) ensureRestorationRoute(target, synchronized(lock) { revision })
            }
        }
        try { platform.activate() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { reportIssue(failure, LifecycleStage.CONNECT) }
        activated = true
        if (connectionIntent == ConnectionIntent.UserDisconnected) return@withLock
        if (id == null || connectionState.isOperational) return@withLock
        if (wifi != null) {
            try { connect(ConnectionTarget.WiFi(wifi.host, wifi.port)); return@withLock }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reporter.report(RuntimeDiagnostic.Failure("activate.wifi", failure)) }
        }
        if (target == null) target = platform.targetForDevice(id)
        if (target == null) {
            reportIssue(ConnectionError.DeviceNotFound(), LifecycleStage.CONNECT)
            return@withLock
        }
        val resolvedTarget = target
        try {
            val route = ensureRestorationRoute(resolvedTarget, synchronized(lock) { revision })
            val state = platform.state(resolvedTarget)
            if (!isRelevant(route)) return@withLock
            if (state.autoReconnecting || state.connected) {
                if (reconnectionCoordinator.reconnectingDeviceId == null) reconnectionCoordinator.handleEnteringAutoReconnect(id)
                if (state.connected) reconnectionCoordinator.handleReconnectionComplete(id)
                return@withLock
            }
            connect(resolvedTarget)
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            reporter.report(RuntimeDiagnostic.Failure("activate.connect", failure))
            startReconnectionWatchdog()
        }
    }

    override suspend fun connect(target: ConnectionTarget) = connect(target, forceFullSync = false, forceReconnect = false)

    suspend fun connect(target: ConnectionTarget, forceFullSync: Boolean, forceReconnect: Boolean) {
        // Swift connect(to:): while connected to a different radio, route through switchDevice, which forces a
        // full sync, refills the preserve budget and reports a failed switch as a connection loss.
        val requested = (target as? ConnectionTarget.Bluetooth)?.deviceId
        val current = connectedDevice?.id
        if (requested != null && current != null && requested != current && connectionState.isOperational) {
            switchDevice(target)
            return
        }
        connectAttempt(target, forceFullSync, forceReconnect)
    }

    private suspend fun connectAttempt(target: ConnectionTarget, forceFullSync: Boolean, forceReconnect: Boolean) {
        currentCoroutineContext().ensureActive()
        val initiatingJob = currentCoroutineContext()[Job]
        val reentrant = currentCoroutineContext()[RuntimeOperation]?.owner === operationIdentity
        requireOpen()
        val normalized = normalizeTarget(target)
        // Swift gates and counts the breaker only on the BLE path; WiFi connects never touch it.
        if (normalized is ConnectionTarget.Bluetooth && !shouldAllowConnection(forceReconnect)) {
            throw ConnectionError.ConnectionFailed("Connection blocked by circuit breaker (cooling down)")
        }
        val deviceId = (normalized as? ConnectionTarget.Bluetooth)?.deviceId
        // Swift evaluates both break-glass conditions before any state change; capture it before CONNECTING is published.
        val breakGlass = forceReconnect && connectionState == DeviceConnectionState.DISCONNECTED
        if (deviceId != null && activeReconnectDeviceId == deviceId) {
            if (forceReconnect && connectionState == DeviceConnectionState.DISCONNECTED && synchronized(lock) { rebuildDevice } != deviceId) {
                reconnectionCoordinator.clearReconnectingDevice()
                disconnectTransport()
            } else {
                setIntent(ConnectionIntent.WantsConnection(forceFullSync))
                if (synchronized(lock) { rebuildDevice } != deviceId) reconnectionCoordinator.restartTimeout(deviceId)
                return
            }
        }
        if (connectedDevice?.id == deviceId && connectionState.isOperational) return
        val submission = synchronized(lock) {
            val duplicate = pending
            if (duplicate != null && !duplicate.isCompleted && !duplicate.isCancelled && pendingTarget == normalized) {
                return@synchronized Submission(duplicate, null, revision, false, null)
            }
            revision = Math.incrementExact(revision)
            val claimedRevision = revision
            val previous = pending
            // Swift connect(to:) routes a connected BLE radio through switchDevice, whose catch fires onConnectionLost.
            val switching = deviceId != null && values.value.state.isConnected
            val oldOwners = detachOwnersLocked(DeviceConnectionState.CONNECTING, ConnectionIntent.WantsConnection(forceFullSync))
            val predecessor = precedingStop
            val oldStop = cleanupScope.async(start = CoroutineStart.LAZY) {
                val inherited = predecessor?.await()
                val own = closeCaptured(oldOwners, disconnectPhysical = true)
                combineReports(inherited, own)
            }.also { precedingStop = it; it.start() }
            val task = processScope.async(context = RuntimeOperation(operationIdentity), start = CoroutineStart.LAZY) {
                withOperation(reentrant) {
                    requireRevision(claimedRevision)
                    val stopped = oldStop.await()
                    if (!stopped.isComplete) reporter.report(RuntimeDiagnostic.Teardown(stopped))
                    requireRevision(claimedRevision)
                    if (!reentrant) previous?.let { withContext(NonCancellable) { it.cancelAndJoin() } }
                    requireRevision(claimedRevision)
                    reconnectionCoordinator.clearReconnectingDevice()
                    stopReconnectionWatchdog(initiatingJob)
                    stopWiFiReconnection(initiatingJob)
                    synchronized(lock) {
                        if (deviceValue?.id != deviceId) { cleanSync = null; attemptedSync = null }
                    }
                    setIntent(ConnectionIntent.WantsConnection(forceFullSync))
                    publish(claimedRevision) { connectingSnapshot() }
                    if (deviceId != null && platform.registryActive && !platform.isRegistered(deviceId)) {
                        throw ConnectionError.DeviceNotFound()
                    }
                    requireRevision(claimedRevision)
                    if (deviceId != null && tryAdoptOrReject(normalized, forceFullSync, breakGlass = breakGlass)) return@withOperation
                    // An owned link whose adoption failed published DISCONNECTED; Swift sets .connecting again.
                    if (connectionState != DeviceConnectionState.CONNECTING) publish(claimedRevision) { connectingSnapshot() }
                    val attempts = if (deviceId == null) 1 else
                        ConnectionRetryPolicy.connectAttempts(forceReconnect, platform.hasSystemPairingRegistry)
                    var lastFailure: Exception? = null
                    for (attempt in 1..attempts) {
                        requireRevision(claimedRevision)
                        val owner = newGeneration(normalized, claimedRevision)
                        try {
                            establish(owner, forceFullSync)
                            requireCurrent(owner)
                            recordConnectionSuccess()
                            stopReconnectionWatchdog(initiatingJob)
                            return@withOperation
                        } catch (cancelled: CancellationException) {
                            if (!isRetained(owner)) closeGeneration(owner, true, cancelled)
                            throw cancelled
                        } catch (failure: Exception) {
                            if (isRetained(owner)) throw CancellationException("Connection transferred to reconnect").apply { initCause(failure) }
                            if (provenForeignAdmission(failure, owner)) {
                                owner.link?.let { PhysicalOwnership.release(it.transport, owner.physicalOwner) }
                                owner.ownsPhysical = false
                            }
                            lastFailure = failure
                            // Swift's cleanupResources leaves state at .connecting between retries; only a terminal
                            // failure publishes DISCONNECTED (finishFailedSubmission), so observers do not flicker.
                            if (!isRetained(owner)) closeGeneration(owner, true, failure, publishDisconnected = false)
                            requireRevision(claimedRevision)
                            val classified = platform.classifyFailure(failure)
                            if (classified is LinkFailure.AuthenticationFailed && deviceId != null) surfaceAuthenticationFailure(deviceId)
                            if (classified is LinkFailure.BluetoothPoweredOff ||
                                classified is LinkFailure.BluetoothUnavailable ||
                                classified is LinkFailure.BluetoothUnauthorized ||
                                classified is LinkFailure.AuthenticationFailed ||
                                failure is ConnectionError.ForeignPhysicalOwner ||
                                hasCause<SessionCorrelationException.ConcurrentTransportOwner>(failure)
                            ) throw failure
                            reporter.report(RuntimeDiagnostic.Failure("connect.attempt.$attempt", failure))
                            publish(claimedRevision) {
                                copy(state = DeviceConnectionState.CONNECTING, token = null, issue = issueFor(failure, LifecycleStage.CONNECT))
                            }
                            if (attempt < attempts) clock.sleep(ConnectionRetryPolicy.connectDelay(attempt, jitter()))
                        }
                    }
                    if (deviceId != null) recordConnectionFailure()
                    throw checkNotNull(lastFailure)
                }
            }
            pending = task
            pendingTarget = normalized
            Submission(task, previous, claimedRevision, true, oldStop, switching && oldOwners.isNotEmpty())
        }
        val work = submission.work
        try {
            if (submission.ownsAttempt) {
                if (!reentrant) submission.previous?.cancel()
                reporter.report(RuntimeDiagnostic.OperationSubmitted(submission.revision))
            }
            work.start()
            work.await()
        }
        catch (cancelled: CancellationException) {
            finishFailedSubmission(submission, cancelled)
            throw cancelled
        } catch (failure: Exception) {
            finishFailedSubmission(submission, failure)
            throw failure
        } finally {
            synchronized(lock) { if (ownsSubmissionLocked(submission)) { pending = null; pendingTarget = null } }
        }
    }

    private fun ownsSubmissionLocked(submission: Submission): Boolean =
        submission.ownsAttempt && !closed && revision == submission.revision && pending === submission.work

    private suspend fun finishFailedSubmission(submission: Submission, primary: Throwable) {
        if (!submission.ownsAttempt) return
        withContext(NonCancellable) {
            val cancelledByOwner = synchronized(lock) {
                if (!ownsSubmissionLocked(submission)) false
                else { submission.work.cancel(); true }
            }
            if (!cancelledByOwner) return@withContext
            submission.work.join()
            try {
                val abortedOwner = synchronized(lock) {
                    if (ownsSubmissionLocked(submission) && submission.work.isCancelled) {
                        active?.takeIf { it.revision == submission.revision && it.operation === submission.work }
                    } else null
                }
                if (abortedOwner != null) {
                    val report = closeGeneration(abortedOwner, true, primary)
                    if (!report.isComplete) reporter.report(RuntimeDiagnostic.Teardown(report))
                }
                val stopped = submission.stop?.await()
                if (stopped != null && !stopped.isComplete) {
                    stopped.issues.forEach { if (it.cause !== primary) primary.addSuppressed(it.cause) }
                    reporter.report(RuntimeDiagnostic.Teardown(stopped))
                }
            }
            catch (cleanup: Exception) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
            val lost = synchronized(lock) {
                // A late reporter failure cannot invalidate a successfully completed shared attempt.
                if (ownsSubmissionLocked(submission) && submission.work.isCancelled && active == null && retained == null) {
                    val issue = if (primary is CancellationException) values.value.issue
                        else issueFor(primary, LifecycleStage.CONNECT)
                    publishLocked(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, issue)
                    submission.detachedLive
                } else false
            }
            // Swift switchDevice catch: cleanupConnection, transport.disconnect, then onConnectionLost before rethrow.
            if (lost) {
                try { observer.onConnectionLost() }
                catch (failure: Exception) { if (failure !== primary) primary.addSuppressed(failure) }
            }
        }
    }

    private fun prepareLink(owner: RadioGeneration): Pair<RuntimeLink, MeshCoreSession> {
        requireCurrent(owner)
        val link = linkFactory.create(owner.target)
        synchronized(lock) { requireCurrent(owner); owner.link = link }
        PhysicalOwnership.acquire(link.transport, owner.physicalOwner)
        owner.ownsPhysical = true
        val sessionClock = object : SessionClock {
            override val now: Duration get() = clock.elapsed
            override val wallClock: java.time.Clock get() = java.time.Clock.fixed(clock.instant, java.time.ZoneOffset.UTC)
            override suspend fun sleepFor(duration: Duration) = clock.sleep(duration)
        }
        val session = MeshCoreSession(
            link.transport, configuration, sessionClock, owner.scope.coroutineContext,
            onDiagnostic = { diagnostic ->
                if (diagnostic is SessionDiagnostic.BackgroundFailure) {
                    reporter.report(RuntimeDiagnostic.Failure(diagnostic.operation, diagnostic.cause))
                }
            },
        )
        synchronized(lock) { requireCurrent(owner); owner.session = session }
        val registration = try { link.register(LinkCallbacks(
            onDisconnected = { failure -> dispatch(owner, "link.disconnected") { handleConnectionLoss(owner, failure) } },
            onAutoReconnecting = { details -> dispatch(owner, "link.autoReconnect") { autoReconnectEntered(owner, details) } },
            onReconnected = { dispatch(owner, "link.reconnected") {
                val id = owner.device?.id ?: (owner.target as? ConnectionTarget.Bluetooth)?.deviceId
                    ?: throw ConnectionError.InvalidIdentity()
                if (owner.restorationRoute && reconnectionCoordinator.reconnectingDeviceId == null) {
                    reconnectionCoordinator.handleEnteringAutoReconnect(id)
                }
                reconnectionCoordinator.handleReconnectionComplete(id)
            } },
            onBondRefreshed = { dispatch(owner, "link.bondRefresh") { persistBondRefresh(owner) } },
        )) } catch (failure: Exception) {
            owner.registrationReady.complete(Unit)
            throw failure
        }
        val installed = synchronized(lock) {
            if (isCurrent(owner) || owner.restorationRoute && isRelevant(owner)) { owner.callbacks = registration; true } else false
        }
        if (!installed) {
            owner.registrationReady.complete(Unit)
            val cancelled = CancellationException("Registration completed for a retired generation")
            try { registration.close() } catch (failure: Exception) { cancelled.addSuppressed(failure) }
            throw cancelled
        }
        owner.registrationReady.complete(Unit)
        val states = session.connectionState
        owner.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            states.collect { state ->
                if (state == ConnectionState.Connected) {
                    owner.sawConnected = true
                    publishFor(owner, DeviceConnectionState.CONNECTED, transport = state)
                } else if (owner.sawConnected && (state == ConnectionState.Disconnected || state is ConnectionState.Failed)) {
                    dispatch(owner, "session.terminal") {
                        if (isCurrent(owner)) handleConnectionLoss(owner, (state as? ConnectionState.Failed)?.error)
                    }
                }
            }
        }
        return link to session
    }

    private suspend fun establish(owner: RadioGeneration, forceFullSync: Boolean, reconnecting: Boolean = false) {
        val (link, session) = prepareLink(owner)
        requireCurrent(owner)
        if (reconnecting) publishFor(owner, DeviceConnectionState.CONNECTED, token = null, issue = null)
        withRuntimeTimeout(10.seconds, "session.start", suspendingClock) {
            session.start(if (reconnecting) 1L else null, disconnectTransportOnFailure = false)
        }
        requireCurrent(owner)
        val info = session.currentSelfInfo ?: throw ConnectionError.InitializationFailed("Failed to get device self info")
        if (info.publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) throw ConnectionError.InvalidIdentity()
        val capabilities = withRuntimeTimeout(10.seconds, "queryDevice", suspendingClock) { session.queryDevice() }
        requireCurrent(owner)
        val detected = DevicePlatform.detect(capabilities.model).let {
            if (link.type == TransportType.WIFI && it == DevicePlatform.UNKNOWN) DevicePlatform.ESP32 else it
        }
        link.configure(capabilities, detected)
        requireCurrent(owner)
        synchronized(lock) { platformValue = detected }
        val deviceId = (owner.target as? ConnectionTarget.Bluetooth)?.deviceId ?: DeviceIdentity.deriveUUID(info.publicKey)
        val existing = devices.fetchDevice(deviceId)
        requireCurrent(owner)
        val byKey = if (existing == null) devices.fetchDevice(info.publicKey) else null
        requireCurrent(owner)
        val prior = existing ?: byKey
        val radioId = prior?.radioId ?: RadioId(DeviceIdentity.deriveUUID(info.publicKey))
        val token = SessionToken(processEpoch, Generation(owner.number), radioId)
        synchronized(lock) { requireCurrent(owner); owner.token = token }
        // Swift: `(try? autoAddConfigResult) ?? AutoAddConfig(bitmask: 0)`. Older firmware reports a typed
        // DeviceError; any other failure is likewise non-fatal, but reported rather than silently defaulted (A-04).
        val autoAdd = bestEffort(
            { if (it is MeshCoreException.DeviceError) "getAutoAddConfig.unsupported" else "getAutoAddConfig" },
            com.meshcoreone.android.core.protocol.model.AutoAddConfig(0u),
        ) { session.getAutoAddConfig() }
        requireCurrent(owner)
        val methods = (owner.target as? ConnectionTarget.WiFi)?.let {
            listOf(ConnectionMethod.WiFi(it.host, it.port))
        } ?: emptyList()
        val device = DeviceDTO.fromConnection(deviceId, radioId, info, capabilities, autoAdd, prior, methods, clock.instant)
        val ownership = FactoryOwnership(token)
        // Publish the ownership under the same lock closeGeneration takes: either teardown already ended this
        // generation (requireCurrent throws before the factory allocates anything) or it sees and closes it.
        synchronized(lock) { requireCurrent(owner); owner.device = device; owner.ownership = ownership }
        val services = serviceFactory.create(RuntimeServiceInputs(
            SessionInputs(token, session, this, owner.scope), device,
            RuntimeServiceCallbacks(
                cleanChannelSync = { synchronized(lock) { if (isCurrent(owner)) cleanSync = radioId to clock.instant } },
                channelSyncAttempted = { synchronized(lock) { if (isCurrent(owner)) attemptedSync = radioId to clock.instant } },
                reconcileIdentity = { reconcileIdentity(token) },
            ),
        ), ownership)
        ownership.verifyReturned(services)
        synchronized(lock) {
            requireCurrent(owner)
            owner.services = OwnedRadioServices(services, ownership, context)
        }
        requireCurrent(owner)
        devices.saveDevice(device)
        requireCurrent(owner)
        if (byKey != null && byKey.id != deviceId) {
            // Swift: `try? deleteDevice` for the orphaned backup-import row.
            bestEffort({ "deleteOrphanDevice" }, Unit) { devices.deleteDevice(byKey.id) }
            requireCurrent(owner)
        }
        // Swift logs and continues: warmUp's hygiene purges are best-effort.
        bestEffort({ "maintenance.warmUp" }, Unit) { maintenance.warmUp() }
        requireCurrent(owner)
        maintenance.initializeDevicePreferences(device)
        requireCurrent(owner)
        services.hydrate()
        requireCurrent(owner)
        // Swift: `(try? session.getRepeatFreq()) ?? []`.
        val ranges = if (capabilities.clientRepeat) {
            bestEffort({ "getRepeatFreq" }, SnapshotList.empty()) { session.getRepeatFreq().snapshot() }
        } else SnapshotList.empty()
        requireCurrent(owner)
        synchronized(lock) {
            requireCurrent(owner)
            deviceValue = device
            repeatRanges = ranges
            publishLocked(DeviceConnectionState.CONNECTED, ConnectionState.Connected, token, null)
        }
        if (link.type == TransportType.BLUETOOTH) {
            lastConnection.persistBondVerification(deviceId)
            requireCurrent(owner)
            link.recordBondVerification(deviceId, clock.instant)
            link.setSessionLive(token)
            requireCurrent(owner)
        }
        lastConnection.persist(deviceId, radioId, info.name)
        requireCurrent(owner)
        observer.onServicesAvailable(token)
        requireCurrent(owner)
        owner.services!!.startMonitoring(MonitoringOptions(enableAutoFetch = false))
        requireCurrent(owner)
        val force = synchronized(lock) {
            val requested = (connectionIntent as? ConnectionIntent.WantsConnection)?.forceFullSync == true
            if (requested) publishLocked(intent = ConnectionIntent.WantsConnection())
            forceFullSync || requested
        }
        requirePublished(owner, DeviceConnectionState.SYNCING)
        val sync = services.initialSync(force)
        requireCurrent(owner)
        when (sync) {
            RuntimeSyncResult.Usable -> {
                val reauth = synchronized(lock) { if (reconnecting) awaitingReauth[radioId]?.toSet() ?: emptySet() else emptySet() }
                if (reauth.isNotEmpty()) services.reauthenticate(reauth)
                requireCurrent(owner)
                synchronized(lock) { awaitingReauth[radioId]?.removeAll(reauth) }
                promoteToReady(owner, syncSucceeded = true)
            }
            is RuntimeSyncResult.Failed -> {
                requirePublished(owner, DeviceConnectionState.SYNCING, issue = issueFor(sync.cause, LifecycleStage.START_MONITORING))
                reporter.report(RuntimeDiagnostic.Failure("initialSync", sync.cause))
                if (link.type == TransportType.WIFI) syncDeviceTimeIfNeeded(owner)
            }
        }
    }

    private suspend fun promoteToReady(owner: RadioGeneration, syncSucceeded: Boolean) {
        requireCurrent(owner)
        requirePublished(owner, if (syncSucceeded) DeviceConnectionState.READY else DeviceConnectionState.SYNCING, issue = null)
        synchronized(lock) { if (isCurrent(owner)) authFailureDevice = null }
        if (syncSucceeded || owner.link?.type == TransportType.WIFI) syncDeviceTimeIfNeeded(owner)
        requireCurrent(owner)
        if (syncSucceeded) {
            owner.services?.services?.ensureListeners() ?: throw ConnectionError.NotConnected()
            requireCurrent(owner)
            observer.onDeviceSynced(checkNotNull(owner.token))
            requireCurrent(owner)
            if (owner.link?.type == TransportType.WIFI) startHeartbeat(owner)
        }
    }

    private suspend fun syncDeviceTimeIfNeeded(owner: RadioGeneration) {
        val session = checkNotNull(owner.session)
        try {
            val time = withRuntimeTimeout(5.seconds, "getTime", suspendingClock) { session.getTime() }
            requireCurrent(owner)
            if (java.time.Duration.between(time, clock.instant).abs() > java.time.Duration.ofSeconds(5)) {
                withRuntimeTimeout(5.seconds, "setTime", suspendingClock) { session.setTime(clock.instant) }
                requireCurrent(owner)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            reporter.report(RuntimeDiagnostic.Failure("syncDeviceTime", failure))
            requireCurrent(owner)
        }
    }

    override suspend fun disconnect(reason: DisconnectReason) {
        when (reason) {
            DisconnectReason.USER_REQUEST -> disconnect(RuntimeDisconnectReason.USER_INITIATED)
            DisconnectReason.DEVICE_SWITCH -> disconnect(RuntimeDisconnectReason.SWITCHING_DEVICE)
            DisconnectReason.PROCESS_SHUTDOWN -> close()
            DisconnectReason.TRANSPORT_LOST, DisconnectReason.SESSION_FAILURE -> disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
        }
    }

    suspend fun disconnect(reason: RuntimeDisconnectReason = RuntimeDisconnectReason.USER_INITIATED): TeardownReport {
        val reentrant = currentCoroutineContext()[RuntimeOperation]?.owner === operationIdentity
        val before = snapshot.value
        val (cleanup, expectedRevision) = synchronized(lock) {
            revision = Math.incrementExact(revision)
            val expectedRevision = revision
            val work = pending
            pending = null
            pendingTarget = null
            deviceValue = null
            repeatRanges = SnapshotList.empty()
            awaitingReauth.clear()
            if (reason.clearsIntent) {
                cleanSync = null; attemptedSync = null; authFailureDevice = null
                publishLocked(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, null, ConnectionIntent.UserDisconnected)
            } else publishLocked(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, null)
            val owners = detachOwnersLocked(DeviceConnectionState.DISCONNECTED)
            val predecessor = precedingStop
            val cleanup = cleanupScope.async(start = CoroutineStart.LAZY) {
                val inherited = predecessor?.await()
                if (!reentrant) work?.cancelAndJoin()
                val own = closeCaptured(owners, disconnectPhysical = true)
                combineReports(inherited, own)
            }
            precedingStop = cleanup
            cleanup to expectedRevision
        }
        reconnectionCoordinator.clearReconnectingDevice()
        stopReconnectionWatchdog()
        stopWiFiReconnection()
        stopHeartbeat()
        cleanup.start()
        // Swift persists the explicit-disconnect intent before teardown. Writing it only after teardown let a
        // process death mid-teardown auto-reconnect on next launch. The write runs beside teardown in the
        // cleanup scope, so caller cancellation cannot skip it, and a storage failure still surfaces afterwards.
        val intentWrite = if (reason.clearsIntent) cleanupScope.async {
            lastConnection.persistIntent(ConnectionIntent.UserDisconnected) { synchronized(lock) { revision == expectedRevision } }
        } else null
        val report = withContext(NonCancellable) { cleanup.await() }
        intentWrite?.await()
        lastConnection.persistDisconnectDiagnostic(
            "source=disconnect(reason), reason=${reason.rawValue}, transport=${transportName(before)}, " +
                "initialState=${before.state.name.lowercase()}, finalState=disconnected, intent=${intentSummary()}",
        )
        if (!report.isComplete) reporter.report(RuntimeDiagnostic.Teardown(report))
        return report
    }

    override fun setConnectionState(state: DeviceConnectionState) {
        publish { copy(state = state, token = if (state == DeviceConnectionState.DISCONNECTED) null else token) }
    }
    override fun clearConnectedDevice() { synchronized(lock) { deviceValue = null; repeatRanges = SnapshotList.empty() } }
    override suspend fun notifyAutoReconnectStarted() = observer.onAutoReconnectStarted()
    override suspend fun notifyConnectionLost() {
        observer.onConnectionLost()
        if (connectionIntent.wantsConnection && connectionState == DeviceConnectionState.DISCONNECTED &&
            currentTransportType() != TransportType.WIFI) startReconnectionWatchdog()
    }
    override suspend fun isTransportAutoReconnecting(): Boolean {
        val owner = synchronized(lock) { active ?: retained } ?: return false
        return platform.state(owner.target).autoReconnecting
    }

    override suspend fun teardownSessionForReconnect() {
        val old = synchronized(lock) {
            active?.also {
                active = null
                retained = it
                deviceValue = null
                repeatRanges = SnapshotList.empty()
                publishLocked(
                    state = if (connectionState == DeviceConnectionState.DISCONNECTED) DeviceConnectionState.DISCONNECTED
                        else DeviceConnectionState.CONNECTING,
                    token = null,
                )
            }
        } ?: return
        if (old.restorationRoute) return
        val report = closeGeneration(old, disconnectPhysical = false)
        old.operation?.cancel()
        if (!report.isComplete) reporter.report(RuntimeDiagnostic.Teardown(report))
    }

    override suspend fun rebuildSession(deviceId: UUID) {
        val reentrant = currentCoroutineContext()[RuntimeOperation]?.owner === operationIdentity
        val expectedCoordinator = reconnectionCoordinator.reconnectGeneration
        val expectedRevision = synchronized(lock) { revision }
        val old = synchronized(lock) { retained ?: active }
        val target = old?.target ?: platform.targetForDevice(deviceId) ?: throw ConnectionError.DeviceNotFound()
        rebuildClaimed(deviceId, target, old, expectedRevision, expectedCoordinator, reentrant)
    }

    private suspend fun rebuildClaimed(
        deviceId: UUID,
        target: ConnectionTarget,
        old: RadioGeneration?,
        expectedRevision: Long,
        expectedCoordinator: Long,
        reentrant: Boolean,
    ) {
        synchronized(lock) {
            if (!wantsCurrent(expectedRevision)) throw CancellationException("Superseded rebuild claim")
            if (rebuildDevice != null && rebuildDevice != deviceId) throw CancellationException("Superseded session rebuild")
            rebuildDevice = deviceId
        }
        try {
            withOperation(reentrant) {
                if (!wantsCurrent(expectedRevision) || reconnectionCoordinator.reconnectGeneration != expectedCoordinator) {
                    throw CancellationException("Superseded session rebuild")
                }
                // WP-107's retained-link receipt is not a new physical generation: close it before reacquisition.
                if (old != null) closeGeneration(old, disconnectPhysical = true)
                synchronized(lock) { if (retained === old) retained = null; if (active === old) active = null }
                val owner = newGeneration(target, expectedRevision)
                try {
                    establish(owner, (connectionIntent as? ConnectionIntent.WantsConnection)?.forceFullSync == true, reconnecting = true)
                    requireCurrent(owner)
                    if (reconnectionCoordinator.reconnectGeneration != expectedCoordinator) throw CancellationException("Stale rebuild completion")
                    recordConnectionSuccess()
                    stopReconnectionWatchdog()
                } catch (failure: Exception) {
                    closeGeneration(owner, disconnectPhysical = false, primary = failure)
                    synchronized(lock) { if (active === owner) { active = null; retained = owner } }
                    throw failure
                }
            }
        } finally {
            synchronized(lock) { if (rebuildDevice == deviceId) rebuildDevice = null }
        }
    }

    override suspend fun disconnectTransport() {
        val owner = synchronized(lock) { retained ?: active } ?: return
        val report = closeGeneration(owner, true)
        synchronized(lock) { if (retained === owner) retained = null; if (active === owner) active = null }
        if (!report.isComplete) reporter.report(RuntimeDiagnostic.Teardown(report))
    }

    override suspend fun handleReconnectionFailure() {
        val expectedRevision = synchronized(lock) { revision }
        val owner = synchronized(lock) {
            if (connectionIntent.wantsConnection) rebuildFailures++
            (active ?: retained).also {
                active = null
                retained = it
                deviceValue = null
                repeatRanges = SnapshotList.empty()
                publishLocked(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, null)
            }
        }
        val state = owner?.let { platform.state(it.target) }
        if (synchronized(lock) { revision != expectedRevision || closed }) {
            if (owner != null) {
                val report = closeGeneration(owner, true)
                if (!report.isComplete) reporter.report(RuntimeDiagnostic.Teardown(report))
            }
            return
        }
        val preserve = connectionIntent.wantsConnection && state != null &&
            (state.connected || state.autoReconnecting) &&
            consecutiveRebuildFailures <= ConnectionRetryPolicy.MAX_REBUILD_FAILURES_PRESERVING_LINK
        if (owner != null) {
            val report = closeGeneration(owner, !preserve)
            if (!report.isComplete) reporter.report(RuntimeDiagnostic.Teardown(report))
            if (!preserve) synchronized(lock) { if (retained === owner) retained = null }
        }
        if (preserve) {
            if (!isReconnectionWatchdogRunning) startReconnectionWatchdog()
            observer.onConnectionLost()
        } else {
            if (connectionIntent.wantsConnection && state != null && (state.connected || state.autoReconnecting)) {
                lastConnection.persistDisconnectDiagnostic("source=handleReconnectionFailure.preserveBudgetExhausted, failures=$consecutiveRebuildFailures, intent=${intentSummary()}")
            }
            notifyConnectionLost()
        }
    }

    suspend fun checkBLEConnectionHealth() {
        if (currentTransportType() == TransportType.WIFI || shouldDeferOpportunisticReconnect || !connectionIntent.wantsConnection) return
        val initialClaim = synchronized(lock) {
            if (pending != null || !connectionIntent.wantsConnection || closed) null
            else revision to (retained ?: active)
        } ?: return
        val expectedRevision = initialClaim.first
        val last = lastConnection.read()
        if (!wantsCurrent(expectedRevision)) return
        val deviceId = last.deviceId ?: return
        if (activeReconnectDeviceId == deviceId) return
        val target = platform.targetForDevice(deviceId) ?: return reportHealthReconnectFailure(ConnectionError.DeviceNotFound())
        if (!wantsCurrent(expectedRevision)) return
        val state = platform.state(target)
        if (!wantsCurrent(expectedRevision)) return
        if (state.connected) {
            if (state.autoReconnecting || state.connectedDeviceId != null && state.connectedDeviceId != deviceId) return
            val owner = synchronized(lock) { active }
            if (owner?.services != null && owner.session != null && connectedDevice?.id == deviceId) {
                if (connectionState.isOperational) owner.services!!.services.ensureListeners()
                return
            }
            val claim = synchronized(lock) {
                if (!wantsCurrent(expectedRevision) || pending != null || rebuildDevice != null ||
                    (retained ?: active) !== initialClaim.second) null
                else {
                    rebuildDevice = deviceId
                    initialClaim.second to reconnectionCoordinator.reconnectGeneration
                }
            } ?: return
            try { rebuildClaimed(deviceId, target, claim.first, expectedRevision, claim.second, reentrant = false) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                reporter.report(RuntimeDiagnostic.Failure("health.rebuild", failure))
                handleReconnectionFailure()
            } finally { synchronized(lock) { if (rebuildDevice == deviceId) rebuildDevice = null } }
            return
        }
        if (state.autoReconnecting || state.bluetoothPoweredOff) return
        if (connectionState.isConnected) synchronized(lock) { active }?.let { handleConnectionLoss(it, null) }
        if (!wantsCurrent(expectedRevision)) return
        if (tryAdoptOrReject(target, false, health = true)) return
        if (!wantsCurrent(expectedRevision)) return
        // Swift attemptOpportunisticReconnect logs and returns; connect already surfaced auth failures.
        try { connect(target) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { reportHealthReconnectFailure(failure) }
    }

    private fun reportHealthReconnectFailure(failure: Exception) =
        reporter.report(RuntimeDiagnostic.Failure("health.reconnect", failure))

    suspend fun checkWiFiConnectionHealth() {
        if (synchronized(lock) { wifiReconnect?.isActive == true } || currentTransportType() == TransportType.BLUETOOTH) return
        val expectedRevision = synchronized(lock) { revision }
        val owner = synchronized(lock) { active }
        if (owner?.link?.type == TransportType.WIFI && connectionState.isOperational && !owner.link!!.transport.isConnected()) {
            if (!wantsCurrent(expectedRevision)) return
            handleConnectionLoss(owner, null)
            return
        }
        if (connectionState == DeviceConnectionState.DISCONNECTED && connectionIntent.wantsConnection) {
            val id = lastConnection.read().deviceId ?: return
            if (!wantsCurrent(expectedRevision)) return
            // Swift: `try? fetchDevice`, then a do/catch that logs a failed foreground reconnect and returns.
            val device = try { devices.fetchDevice(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reportWiFiHealthReconnectFailure(failure); return } ?: return
            if (!wantsCurrent(expectedRevision)) return
            val wifi = device.connectionMethods.filterIsInstance<ConnectionMethod.WiFi>().firstOrNull() ?: return
            try { connect(ConnectionTarget.WiFi(wifi.host, wifi.port)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { reportWiFiHealthReconnectFailure(failure) }
        }
    }

    private fun reportWiFiHealthReconnectFailure(failure: Exception) =
        reporter.report(RuntimeDiagnostic.Failure("health.wifiReconnect", failure))

    suspend fun appDidEnterBackground() {
        synchronized(lock) { foreground = false }
        platform.foreground(false)
        stopReconnectionWatchdog()
    }
    suspend fun appDidBecomeActive() {
        val expectedRevision = synchronized(lock) { revision }
        synchronized(lock) { foreground = true }
        platform.foreground(true)
        checkWiFiConnectionHealth()
        checkBLEConnectionHealth()
        if (!wantsCurrent(expectedRevision) || connectionState != DeviceConnectionState.DISCONNECTED ||
            shouldDeferOpportunisticReconnect || currentTransportType() == TransportType.WIFI) return
        val autoReconnecting = isTransportAutoReconnecting()
        if (wantsCurrent(expectedRevision) && connectionState == DeviceConnectionState.DISCONNECTED && !autoReconnecting) {
            startReconnectionWatchdog()
        }
    }
    fun setPairingActivity(pairingInProgress: Boolean, pairingFlowActive: Boolean) {
        synchronized(lock) { pairing = pairingInProgress; pairingFlow = pairingFlowActive }
    }

    fun shouldAllowConnection(force: Boolean): Boolean = synchronized(lock) { breaker.allows(force) }
    fun recordConnectionFailure() { synchronized(lock) { breaker.recordFailure() } }
    fun recordConnectionSuccess() { synchronized(lock) { rebuildFailures = 0; breaker.recordSuccess() } }
    fun resetPreserveBudgetAfterDeviceSwitch() = recordConnectionSuccess()

    suspend fun switchDevice(target: ConnectionTarget) {
        synchronized(lock) { cleanSync = null; attemptedSync = null }
        // The owned submission reports connection loss once, after its captured generation is torn down.
        connectAttempt(target, forceFullSync = true, forceReconnect = false)
        if (connectionState.isOperational) resetPreserveBudgetAfterDeviceSwitch()
    }

    suspend fun reconcileIdentity(expected: SessionToken): RadioId? {
        val owner = synchronized(lock) { active?.takeIf { it.token == expected } } ?: return staleIdentity(expected)
        val before = owner.device ?: throw ConnectionError.NotConnected()
        val info = owner.session?.currentSelfInfo ?: throw ConnectionError.InitializationFailed("No self info")
        val radioId = devices.reconcileGhostIdentity(before.id, info.publicKey)
        if (before.publicKey != info.publicKey) {
            val vKey = VContactIdentity.publicKey(before.publicKey)
            if (vKey != null) {
                contacts.fetchContact(before.radioId, vKey)?.let { contacts.deleteContact(EntityKey(it.radioId, it.id)) }
            }
        }
        if (!isCurrent(owner)) return radioId
        if (radioId != null) {
            val refreshed = devices.fetchDevice(before.id) ?: throw ConnectionError.DeviceNotFound()
            requireCurrent(owner)
            synchronized(lock) { deviceValue = refreshed; owner.device = refreshed }
            lastConnection.persist(refreshed.id, refreshed.radioId, refreshed.nodeName)
            // A graph's radio partition is immutable: rebuild rather than retag its pending sends.
            dispatch(owner, "identity.rebuild") { disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP); connect(owner.target, true, true) }
        }
        return radioId
    }

    suspend fun clearPersistedConnection(deviceId: UUID) {
        val owner = synchronized(lock) { bondPersistEpoch++; active ?: retained }
        owner?.link?.clearBondVerification(deviceId)
        if (owner?.device?.id == deviceId) owner.link?.setSessionLive(null)
        val holderCleared = bondPersistence.withLock { lastConnection.clear(deviceId) }
        if (holderCleared) observer.onLastDeviceCleared()
    }

    suspend fun close(): TeardownReport {
        val reentrant = currentCoroutineContext()[RuntimeOperation]?.owner === operationIdentity
        val except = if (reentrant) synchronized(lock) { pending } else null
        return withContext(NonCancellable) { startShutdown(null, except).await() }
    }
    suspend fun awaitShutdown(): TeardownReport = withContext(NonCancellable) {
        val work = synchronized(lock) { shutdown } ?: throw IllegalStateException("Runtime has not shut down")
        work.await()
    }

    private fun startShutdown(cause: Throwable?, except: Job? = null): Deferred<TeardownReport> = synchronized(lock) {
        shutdown ?: cleanupScope.async(start = CoroutineStart.LAZY) {
            synchronized(lock) { closed = true; revision++ }
            reconnectionCoordinator.close()
            stopReconnectionWatchdog()
            stopWiFiReconnection()
            stopHeartbeat()
            val work = synchronized(lock) { pending.also { pending = null; pendingTarget = null } }
            if (work !== except) work?.cancelAndJoin()
            val preceding = synchronized(lock) { precedingStop }
            val precedingReport = preceding?.await()
            val currentReport = stopCurrent(true)
            val report = TeardownReport(((precedingReport?.issues ?: emptyList()) + currentReport.issues).snapshot())
            publish { copy(state = DeviceConnectionState.DISCONNECTED, transport = ConnectionState.Disconnected, token = null) }
            transitions.finish(cause)
            processJob.cancel()
            reporter.report(RuntimeDiagnostic.Teardown(report))
            report
        }.also { work ->
            shutdown = work
            work.invokeOnCompletion { cleanupJob.cancel() }
            work.start()
        }
    }

    private fun newGeneration(target: ConnectionTarget, claimedRevision: Long): RadioGeneration = synchronized(lock) {
        requireRevision(claimedRevision)
        nextGeneration = Math.incrementExact(nextGeneration)
        RadioGeneration(nextGeneration, target, claimedRevision, context, processJob).also {
            active = it
            it.operation = pending
        }
    }

    private suspend fun <T> withOperation(reentrant: Boolean, action: suspend () -> T): T =
        if (reentrant) action() else operations.withLock { action() }

    private suspend fun closeGeneration(
        owner: RadioGeneration, disconnectPhysical: Boolean, primary: Throwable? = null,
        publishDisconnected: Boolean = true,
    ): TeardownReport {
        synchronized(lock) {
            if (primary != null && provenForeignAdmission(primary, owner)) {
                owner.link?.let { PhysicalOwnership.release(it.transport, owner.physicalOwner) }
                owner.ownsPhysical = false
            }
            owner.logicalEnded = true
            if (disconnectPhysical) owner.physicalEnding = true
            if (active === owner && disconnectPhysical) {
                active = null
                deviceValue = null
                repeatRanges = SnapshotList.empty()
                if (publishDisconnected && revision == owner.revision) {
                    publishLocked(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, values.value.issue)
                }
            }
        }
        val serviceWork = synchronized(owner.lock) {
            owner.servicesClosed ?: cleanupScope.async(start = CoroutineStart.LAZY) {
                val issues = mutableListOf<TeardownIssue>()
                suspend fun attempt(stage: LifecycleStage, action: suspend () -> Unit) {
                    try { action() } catch (failure: Exception) { issues += TeardownIssue(stage, failure) }
                }
                // Stop ingestion before rebuilding; explicit disconnect tears services down first below.
                if (!disconnectPhysical) attempt(LifecycleStage.CLOSE_TRANSPORT) { owner.session?.stop(false) }
                attempt(LifecycleStage.STOP_SERVICES) {
                    owner.services?.services?.remoteDisconnected()?.let {
                        synchronized(lock) {
                            val radio = owner.token?.radioId
                            if (!disconnectPhysical && !closed && connectionIntent.wantsConnection && radio != null) {
                                awaitingReauth.getOrPut(radio) { mutableSetOf() } += it
                            }
                        }
                    }
                }
                attempt(LifecycleStage.STOP_SERVICES) { owner.services?.services?.resetSyncState() }
                owner.services?.let { issues += it.close().issues }
                    ?: owner.ownership?.let { issues += it.close().issues }
                owner.job.cancelAndJoin()
                attempt(LifecycleStage.STOP_SERVICES) { owner.link?.setSessionLive(null) }
                TeardownReport(issues.snapshot())
            }.also { owner.servicesClosed = it; it.start() }
        }
        val serviceReport = withContext(NonCancellable) { serviceWork.await() }
        val physicalReport = if (disconnectPhysical) {
            val physical = synchronized(owner.lock) {
                owner.physicalClosed ?: cleanupScope.async(start = CoroutineStart.LAZY) {
                    val issues = mutableListOf<TeardownIssue>()
                    try { owner.callbacks?.close() }
                    catch (failure: Exception) { issues += TeardownIssue(LifecycleStage.STOP_SERVICES, failure) }
                    try {
                        if (owner.ownsPhysical) owner.session?.stop(true)
                    } catch (failure: Exception) { issues += TeardownIssue(LifecycleStage.CLOSE_TRANSPORT, failure) }
                    finally {
                        val link = owner.link
                        if (link != null && owner.ownsPhysical) {
                            try {
                                if (!link.transport.isConnected()) {
                                    PhysicalOwnership.release(link.transport, owner.physicalOwner)
                                    owner.ownsPhysical = false
                                } else issues += TeardownIssue(LifecycleStage.CLOSE_TRANSPORT, ConnectionError.RetainedPhysicalLink())
                            } catch (failure: Exception) { issues += TeardownIssue(LifecycleStage.CLOSE_TRANSPORT, failure) }
                        }
                    }
                    TeardownReport(issues.snapshot())
                }.also { owner.physicalClosed = it; it.start() }
            }
            withContext(NonCancellable) { physical.await() }
        } else TeardownReport(emptyList<TeardownIssue>().snapshot())
        val result = TeardownReport((serviceReport.issues + physicalReport.issues).snapshot())
        if (primary != null) result.issues.forEach { if (it.cause !== primary) primary.addSuppressed(it.cause) }
        synchronized(lock) {
            if (active === owner && disconnectPhysical) {
                active = null
                deviceValue = null
                repeatRanges = SnapshotList.empty()
            }
        }
        return result
    }

    private suspend fun stopCurrent(disconnectPhysical: Boolean): TeardownReport {
        val owners = synchronized(lock) { detachOwnersLocked(DeviceConnectionState.DISCONNECTED) }
        return closeCaptured(owners, disconnectPhysical)
    }

    private fun detachOwnersLocked(state: DeviceConnectionState, intent: ConnectionIntent = values.value.intent): List<RadioGeneration> {
        val owners = listOfNotNull(active, retained).distinct()
        active = null
        retained = null
        deviceValue = null
        repeatRanges = SnapshotList.empty()
        publishLocked(state, if (state == DeviceConnectionState.CONNECTING) ConnectionState.Connecting else ConnectionState.Disconnected,
            null, values.value.issue, intent)
        return owners
    }

    private suspend fun closeCaptured(owners: List<RadioGeneration>, disconnectPhysical: Boolean): TeardownReport =
        TeardownReport(owners.flatMap { closeGeneration(it, disconnectPhysical).issues }.snapshot())

    private fun combineReports(inherited: TeardownReport?, own: TeardownReport): TeardownReport =
        TeardownReport(((inherited?.issues ?: emptyList()) + own.issues).distinct().snapshot())

    private fun provenForeignAdmission(failure: Throwable, owner: RadioGeneration): Boolean =
        owner.ownsPhysical && !owner.sawConnected &&
            (hasCause<SessionCorrelationException.ConcurrentTransportOwner>(failure) ||
                hasCause<SessionCorrelationException.RetainedTransport>(failure))

    private suspend fun handleConnectionLoss(owner: RadioGeneration, failure: Throwable?) {
        if (!isRelevant(owner)) { reporter.report(RuntimeDiagnostic.StaleCallback("connectionLoss", owner.number)); return }
        val before = snapshot.value
        reconnectionCoordinator.clearReconnectingDevice()
        synchronized(lock) {
            if (active === owner) active = null
            retained = owner
            deviceValue = null
            repeatRanges = SnapshotList.empty()
            publishLocked(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null,
                failure?.let { issueFor(it, LifecycleStage.CONNECT) })
        }
        val report = closeGeneration(owner, false, failure)
        owner.operation?.cancel()
        if (!report.isComplete) reporter.report(RuntimeDiagnostic.Teardown(report))
        if (!isRelevant(owner)) return
        lastConnection.persistDisconnectDiagnostic("source=handleConnectionLoss, stateBefore=${before.state.name.lowercase()}, error=${failure?.javaClass?.simpleName ?: "none"}, intent=${intentSummary()}")
        if (!isRelevant(owner)) return
        if (failure != null && platform.classifyFailure(failure) is LinkFailure.AuthenticationFailed) {
            (owner.target as? ConnectionTarget.Bluetooth)?.deviceId?.let(::surfaceAuthenticationFailure)
        }
        observer.onConnectionLost()
        if (owner.link?.type == TransportType.WIFI) startWiFiReconnection(owner.target)
        else if (connectionIntent.wantsConnection) startReconnectionWatchdog()
    }

    private suspend fun autoReconnectEntered(owner: RadioGeneration, details: String) {
        val id = (owner.target as? ConnectionTarget.Bluetooth)?.deviceId ?: return
        if (shouldDeferOpportunisticReconnect) { handleConnectionLoss(owner, null); return }
        val manual = synchronized(lock) { (pendingTarget as? ConnectionTarget.Bluetooth)?.deviceId }
        if (manual != null && manual != id) return
        if (manual == id) synchronized(lock) { pendingTarget = null }
        reconnectionCoordinator.handleEnteringAutoReconnect(id)
        if (!isRelevant(owner)) return
        lastConnection.persistDisconnectDiagnostic("source=bleStateMachine.autoReconnectingHandler, error=$details, intent=${intentSummary()}")
    }

    private suspend fun tryAdoptOrReject(
        target: ConnectionTarget, forceFullSync: Boolean, health: Boolean = false, breakGlass: Boolean = false,
    ): Boolean {
        val expectedRevision = synchronized(lock) { revision }
        val deviceId = (target as? ConnectionTarget.Bluetooth)?.deviceId ?: return false
        val state = platform.state(target)
        if (!wantsCurrent(expectedRevision)) throw CancellationException("Superseded platform query")
        if (state.autoReconnecting) {
            if (state.connectedDeviceId != deviceId) return false
            if (breakGlass) {
                // Swift's second break-glass (connect(to:) → abandonStuckReconnect): the coordinator's cycle is already
                // gone but the transport is still auto-reconnecting this device, so a user-forced connect from
                // DISCONNECTED tears the pending connect down and falls through to a fresh attempt that can surface
                // the real failure (e.g. an invalidated bond) instead of deferring to the same doomed wait again.
                reconnectionCoordinator.clearReconnectingDevice()
                closeGeneration(ensureRestorationRoute(target, expectedRevision), disconnectPhysical = true)
                if (!wantsCurrent(expectedRevision)) throw CancellationException("Superseded break-glass")
                return false
            }
            setIntent(ConnectionIntent.WantsConnection(forceFullSync))
            ensureRestorationRoute(target, expectedRevision)
            reconnectionCoordinator.restartTimeout(deviceId)
            return true
        }
        if (!state.systemConnected) return false
        val ours = lastConnection.read().deviceId == deviceId ||
            synchronized(lock) { pairing } || platform.hasSystemPairingRegistry && platform.isRegistered(deviceId)
        if (!wantsCurrent(expectedRevision)) throw CancellationException("Superseded system-link query")
        var route: RadioGeneration? = null
        if (ours && state.phase == "idle") {
            setIntent(ConnectionIntent.WantsConnection(forceFullSync))
            route = ensureRestorationRoute(target, expectedRevision)
            reconnectionCoordinator.handleEnteringAutoReconnect(deviceId)
            if (!wantsCurrent(expectedRevision)) throw CancellationException("Superseded adoption preparation")
            val adopted = platform.adoptSystemLink(target)
            if (!wantsCurrent(expectedRevision)) throw CancellationException("Superseded adoption completion")
            if (adopted) {
                lastConnection.persistDisconnectDiagnostic("source=${if (health) "checkBLEConnectionHealth" else "connect(to:)"}.adoptSystemConnectedPeripheral, intent=${intentSummary()}")
                return true
            }

            reconnectionCoordinator.clearReconnectingDevice()
            publish { copy(state = DeviceConnectionState.DISCONNECTED, token = null) }
        }
        // Swift throws deviceConnectedToOtherApp only for a link that is not ours; an owned link
        // that could not be adopted proceeds to a normal connect. The health check keeps its own rule.
        if (ours && !health) {
            // Release the adoption route (link, callbacks, physical claim) before the normal attempt builds its own.
            route?.let { closeGeneration(it, disconnectPhysical = true) }
            if (!wantsCurrent(expectedRevision)) throw CancellationException("Superseded owned-link fallthrough")
            return false
        }
        lastConnection.persistDisconnectDiagnostic("source=checkBLEConnectionHealth.otherAppConnected, intent=${intentSummary()}")
        if (health) { if (!isReconnectionWatchdogRunning) startReconnectionWatchdog(); return true }
        throw LinkFailure.DeviceConnectedToOtherApp()
    }

    private fun ensureRestorationRoute(target: ConnectionTarget, expectedRevision: Long): RadioGeneration {
        val existing = synchronized(lock) { (active ?: retained)?.takeIf { it.target == target && it.revision == expectedRevision } }
        if (existing != null) return existing
        val owner = newGeneration(target, expectedRevision)
        owner.restorationRoute = true
        prepareLink(owner)
        return owner
    }

    fun startReconnectionWatchdog() {
        stopReconnectionWatchdog()
        val claimed = synchronized(lock) { watchdogGeneration = Math.incrementExact(watchdogGeneration); watchdogGeneration }
        val task = processScope.launch(start = CoroutineStart.LAZY) {
            var attempt = 1
            while (true) {
                clock.sleep(ConnectionRetryPolicy.watchdogDelay(attempt))
                if (!connectionIntent.wantsConnection || connectionState != DeviceConnectionState.DISCONNECTED || !synchronized(lock) { foreground }) return@launch
                try {
                    if (!shouldDeferOpportunisticReconnect && !isTransportAutoReconnecting()) checkBLEConnectionHealth()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { reporter.report(RuntimeDiagnostic.Failure("watchdog", failure)) }
                // A connect started from this loop stops the watchdog without cancelling its own caller;
                // once superseded, finish here instead of looping on untracked.
                if (synchronized(lock) { watchdogGeneration != claimed }) return@launch
                if (!connectionIntent.wantsConnection || connectionState != DeviceConnectionState.DISCONNECTED) return@launch
                attempt = minOf(attempt + 1, 3)
            }
        }
        synchronized(lock) { watchdog = task }
        task.invokeOnCompletion { synchronized(lock) { if (watchdogGeneration == claimed) watchdog = null } }
        task.start()
    }
    fun stopReconnectionWatchdog(except: Job? = null) {
        val old = synchronized(lock) { watchdogGeneration++; watchdog.also { watchdog = null } }
        if (old !== except) old?.cancel()
    }

    private fun startWiFiReconnection(target: ConnectionTarget) {
        if (!connectionIntent.wantsConnection) return
        val task = synchronized(lock) {
            if (wifiReconnect?.isActive == true) return
            val previous = lastWiFiReconnect
            if (previous != null && clock.elapsed - previous < ConnectionRetryPolicy.WIFI_RECONNECT_COOLDOWN) {
                publishLocked(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, null)
                return
            }
            lastWiFiReconnect = clock.elapsed
            processScope.launch(start = CoroutineStart.LAZY) {
                val started = clock.elapsed
                var attempt = 1
                while (connectionIntent.wantsConnection && clock.elapsed - started <= ConnectionRetryPolicy.WIFI_RECONNECT_WINDOW) {
                    try { connect(target); return@launch }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { reporter.report(RuntimeDiagnostic.Failure("wifi.reconnect.$attempt", failure)) }
                    clock.sleep(ConnectionRetryPolicy.wifiDelay(attempt++))
                }
                publish { copy(state = DeviceConnectionState.DISCONNECTED, transport = ConnectionState.Disconnected, token = null) }
            }.also { wifiReconnect = it }
        }
        task.invokeOnCompletion { synchronized(lock) { if (wifiReconnect === task) wifiReconnect = null } }
        task.start()
    }
    private fun stopWiFiReconnection(except: Job? = null) {
        val old = synchronized(lock) { wifiReconnect.also { if (it !== except) wifiReconnect = null } }
        if (old !== except) old?.cancel()
    }
    private fun startHeartbeat(owner: RadioGeneration) {
        stopHeartbeat()
        val task = owner.scope.launch(start = CoroutineStart.LAZY) {
            while (isCurrent(owner)) {
                clock.sleep(30.seconds)
                if (connectionState != DeviceConnectionState.READY) continue
                try { checkNotNull(owner.session).getTime() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    dispatch(owner, "wifi.heartbeat") { handleConnectionLoss(owner, failure) }
                    return@launch
                }
            }
        }
        synchronized(lock) { heartbeat = task }
        task.start()
    }
    private fun stopHeartbeat() { synchronized(lock) { heartbeat.also { heartbeat = null } }?.cancel() }

    private suspend fun persistBondRefresh(owner: RadioGeneration) {
        val epoch = synchronized(lock) { bondPersistEpoch }
        val id = owner.device?.id ?: return
        if (owner.link?.mayRefreshBond(id) != true) return
        bondPersistence.withLock {
            lastConnection.persistBondVerification(id) {
                synchronized(lock) { epoch == bondPersistEpoch && isCurrent(owner) }
            }
        }
    }
    private fun surfaceAuthenticationFailure(id: UUID) {
        val fire = synchronized(lock) { if (authFailureDevice == id) false else { authFailureDevice = id; true } }
        if (fire) observer.onAuthenticationFailure(id)
    }

    /** Clears the auth-failure latch so the next failure episode for the same device re-presents recovery. */
    fun clearSurfacedAuthenticationFailure() { synchronized(lock) { authFailureDevice = null } }

    /** Swift updateDevice(with:) after a device edit, limited to the current generation's radio. */
    fun replaceConnectedDevice(device: DeviceDTO) {
        synchronized(lock) {
            val owner = active ?: return
            val current = deviceValue ?: return
            if (!isCurrent(owner) || current.id != device.id || current.radioId != device.radioId) return
            deviceValue = device
            owner.device = device
        }
    }

    private fun dispatch(owner: RadioGeneration, operation: String, action: suspend () -> Unit) {
        processScope.launch {
            owner.registrationReady.await()
            if (!isRelevant(owner)) { reporter.report(RuntimeDiagnostic.StaleCallback(operation, owner.number)); return@launch }
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                reporter.report(RuntimeDiagnostic.Failure(operation, failure))
                if (isRelevant(owner)) reportIssue(failure, LifecycleStage.CONNECT)
            }
        }
    }
    private fun isCurrent(owner: RadioGeneration): Boolean = synchronized(lock) {
        !closed && !owner.logicalEnded && revision == owner.revision && active === owner && connectionIntent.wantsConnection
    }
    private fun isRelevant(owner: RadioGeneration): Boolean = synchronized(lock) {
        !closed && !owner.physicalEnding && revision == owner.revision && (active === owner || retained === owner)
    }
    private fun isRetained(owner: RadioGeneration): Boolean = synchronized(lock) { retained === owner }
    private fun requireCurrent(owner: RadioGeneration) {
        if (!isCurrent(owner)) throw CancellationException("Superseded connection generation")
    }
    private fun requireRevision(expected: Long) {
        synchronized(lock) { if (closed || revision != expected) throw CancellationException("Superseded connection request") }
    }
    private fun wantsCurrent(expected: Long): Boolean = synchronized(lock) {
        !closed && revision == expected && connectionIntent.wantsConnection
    }
    private fun requireOpen() { synchronized(lock) { check(!closed && processJob.isActive) { "Connection runtime is closed" } } }
    private fun currentTransportType(): TransportType? = synchronized(lock) { (active ?: retained)?.link?.type }
    private suspend fun setIntent(intent: ConnectionIntent) {
        val expected = synchronized(lock) { revision }
        lastConnection.persistIntent(intent) { synchronized(lock) { revision == expected && !closed } }
        synchronized(lock) {
            requireRevision(expected)
            publishLocked(intent = intent)
        }
    }
    private fun intentSummary(): String = when (val intent = connectionIntent) {
        ConnectionIntent.None -> "none"
        ConnectionIntent.UserDisconnected -> "userDisconnected"
        is ConnectionIntent.WantsConnection -> if (intent.forceFullSync) "wantsConnection(forceFullSync: true)" else "wantsConnection"
    }
    private fun transportName(value: ConnectionSnapshot): String =
        value.blePhase?.let { "bluetooth" } ?: currentTransportType()?.name?.lowercase() ?: "none"
    /**
     * Publishes [change] applied to the snapshot that is current under [lock], so fields the caller
     * leaves alone cannot be captured before a racing publication and written back stale.
     * With [expectedRevision], a superseded attempt publishes nothing and returns false.
     */
    private fun publish(
        expectedRevision: Long? = null,
        change: ConnectionSnapshot.() -> ConnectionSnapshot,
    ): Boolean = synchronized(lock) {
        if (expectedRevision != null && (closed || revision != expectedRevision)) return@synchronized false
        val next = values.value.change()
        publishLocked(next.state, next.transport, next.token, next.issue)
        true
    }
    private fun ConnectionSnapshot.connectingSnapshot(): ConnectionSnapshot =
        copy(state = DeviceConnectionState.CONNECTING, transport = ConnectionState.Connecting, token = null, issue = null)
    private fun publishFor(
        owner: RadioGeneration,
        state: DeviceConnectionState,
        transport: ConnectionState = values.value.transport,
        token: SessionToken? = owner.token,
        issue: ConnectionIssue? = values.value.issue,
    ): Boolean = synchronized(lock) {
        if (!isCurrent(owner)) return@synchronized false
        publishLocked(state, transport, token, issue)
        true
    }
    private fun requirePublished(
        owner: RadioGeneration,
        state: DeviceConnectionState,
        issue: ConnectionIssue? = values.value.issue,
    ) {
        if (!publishFor(owner, state, issue = issue)) throw CancellationException("Stale state publication")
    }
    private fun publishLocked(
        state: DeviceConnectionState = values.value.state,
        transport: ConnectionState = values.value.transport,
        token: SessionToken? = values.value.token,
        issue: ConnectionIssue? = values.value.issue,
        intent: ConnectionIntent = values.value.intent,
    ) {
        if (state.isOperational) check(active?.services != null && active?.session != null && deviceValue != null)
        val next = ConnectionSnapshot(state, transport, null, intent, token, issue)
        values.value = next
        transitions.yield(next)
    }
    /** Source `try?` paths: cancellation propagates; any other failure is reported, then [fallback] is used. */
    private suspend fun <T> bestEffort(operation: (Exception) -> String, fallback: T, block: suspend () -> T): T =
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            reporter.report(RuntimeDiagnostic.Failure(operation(failure), failure))
            fallback
        }
    private fun issueFor(failure: Throwable, stage: LifecycleStage): ConnectionIssue = when (failure) {
        is MeshCoreException -> ConnectionIssue.Protocol(failure)
        is PersistenceStoreException -> ConnectionIssue.Storage(failure)
        else -> ConnectionIssue.Lifecycle(stage, failure)
    }
    private fun reportIssue(failure: Throwable, stage: LifecycleStage) {
        reporter.report(RuntimeDiagnostic.Failure(stage.name, failure))
        publish { copy(issue = issueFor(failure, stage)) }
    }
    private fun staleIdentity(token: SessionToken): RadioId? {
        reporter.report(RuntimeDiagnostic.StaleCallback("identity", token.generation.value))
        return null
    }
    private fun normalizeTarget(target: ConnectionTarget): ConnectionTarget = when (target) {
        is ConnectionTarget.Bluetooth -> target
        is ConnectionTarget.WiFi -> {
            val host = target.host.trim()
            if (host.isEmpty()) throw com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException(
                com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError.InvalidHost,
            )
            if (target.port == 0.toUShort()) throw com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportException(
                com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransportError.InvalidPort,
            )
            target.copy(host = host)
        }
    }
    private inline fun <reified T : Throwable> hasCause(failure: Throwable): Boolean {
        var cause: Throwable? = failure
        val seen = mutableSetOf<Throwable>()
        while (cause != null && seen.add(cause)) { if (cause is T) return true; cause = cause.cause }
        return false
    }
}
