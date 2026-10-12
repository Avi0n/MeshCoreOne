// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEStateMachine.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEStateMachine+CallbackHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Transport/iOSBLETransport.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Transport/iOSMeshTransport.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.util.concurrent.atomic.AtomicBoolean
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

class BleTransport(
    private val facade: GattFacade,
    private val configuration: BleConfiguration = BleConfiguration(),
    private val clock: BleClock = MonotonicBleClock(),
    private val wallClock: Clock = Clock.systemUTC(),
) : MeshTransport {
    private class Ingress {
        val packets = Channel<Bytes>(Channel.UNLIMITED)
        private val collecting = AtomicBoolean(false)
        var closed = false
            private set
        private var terminalCause: Throwable? = null

        val flow: Flow<Bytes> = flow {
            if (!collecting.compareAndSet(false, true)) {
                throw BleTransportException(BleError.MultipleReceivers)
            }
            try {
                for (packet in packets) emit(packet)
            } finally {
                collecting.set(false)
            }
        }

        fun markClosed(cause: Throwable?) {
            if (!closed) {
                closed = true
                terminalCause = cause
            }
        }
        fun finish() { packets.close(terminalCause) }
    }

    private data class Completion(val reply: GattReply, val acknowledgedWriteDeadline: Duration?)

    private class Pending(val operation: GattOperation) {
        val result = CompletableDeferred<Completion>()
    }

    private class Connection(
        val generation: Long,
        val gatt: GattConnection,
        val ingress: Ingress,
        var writePacing: Duration,
    ) {
        var phase = BlePhase.Connecting
        var pending: Pending? = null
        var tx: GattCharacteristic? = null
        var rx: GattCharacteristic? = null
        var bond: BondState? = null
        var mtu: Int? = null
        var capabilities: FirmwareFrameCapabilities? = null
        var notificationsRequested = false
        var earliestNextWrite = Duration.ZERO
        var terminated = false
        var terminalCause: Throwable? = null
        val termination = CompletableDeferred<Unit>()
        var closeReceipt: CompletableDeferred<Unit>? = null
        var closeStarted = false
        var liveRadioId: UUID? = null
    }

    private val lock = Any()
    private val connectMutex = Mutex()
    private val operationMutex = Mutex()
    private var generation = 0L
    private var sequence = 0L
    private var intentRevision = 0L
    private var availability = facade.availability()
    private var ingress = Ingress()
    private var active: Connection? = null
    private var closing: Deferred<Unit>? = null
    private var issue: BleError? = null
    private var rejectedCallbacks = 0L
    private var lastRejected: RejectedCallback? = null
    private val bondVerifications = mutableMapOf<UUID, Instant>()
    private val bondEpochs = mutableMapOf<UUID, Long>()
    private var bondRefreshed: ((BondRefresh) -> Unit)? = null
    private val mutableDiagnostics = MutableStateFlow(snapshot())
    val diagnostics: StateFlow<BleLinkDiagnostics> = mutableDiagnostics.asStateFlow()

    override suspend fun connect() = connect(BleConnectMode.Initial)

    suspend fun connect(mode: BleConnectMode) {
        val requestedIntent = synchronized(lock) { intentRevision }
        connectMutex.withLock {
            currentCoroutineContext().ensureActive()
            synchronized(lock) {
                if (requestedIntent != intentRevision) throw BleTransportException(BleError.NotConnected)
                if (active?.phase == BlePhase.Connected) return@withLock
            }
            synchronized(lock) { closing }?.let { awaitClose(it, null) }
            val connection = synchronized(lock) {
                if (requestedIntent != intentRevision) throw BleTransportException(BleError.NotConnected)
                availability = facade.availability()
                availabilityFailure(availability)?.let {
                    issue = it.error
                    ingress.markClosed(it)
                    publish()
                    ingress.finish()
                    throw it
                }
                if (ingress.closed) ingress = Ingress()
                generation = Math.addExact(generation, 1L)
                val gatt = facade.create(events)
                Connection(generation, gatt, ingress, configuration.writePacing).also {
                    active = it
                    issue = null
                    publish()
                }
            }
            var connected = false
            try {
                val link = perform(
                    connection, clock.now + configuration.timeouts.connection,
                    GattOperationKind.Connect,
                ) { GattOperation.Connect(it, configuration.requireBond) }
                if (link !is GattReply.Connected) throw BleTransportException(BleError.InvalidResponse)
                synchronized(lock) {
                    checkConnection(connection)
                    connection.bond = link.bond
                    if (configuration.requireBond && link.bond != BondState.Bonded) {
                        throw BleTransportException(BleError.BondRequired(link.bond), GattOperationKind.Connect)
                    }
                }
                val deadline = clock.now + when (mode) {
                    BleConnectMode.Initial -> configuration.timeouts.discovery
                    BleConnectMode.Reconnect -> configuration.timeouts.reconnectDiscovery
                }
                setPhase(connection, BlePhase.DiscoveringServices)
                val discovered = perform(connection, deadline, GattOperationKind.DiscoverServices) {
                    GattOperation.DiscoverServices(it)
                }
                if (discovered !is GattReply.Services) throw BleTransportException(BleError.InvalidResponse)
                setPhase(connection, BlePhase.DiscoveringCharacteristics)
                val service = discovered.services.singleOrNull { it.uuid == NusUuid.SERVICE }
                    ?: throw BleTransportException(BleError.ServiceNotFound, GattOperationKind.DiscoverServices)
                val tx = service.characteristics.singleOrNull { it.uuid == NusUuid.TX }
                    ?: throw BleTransportException(BleError.CharacteristicNotFound)
                val rx = service.characteristics.singleOrNull { it.uuid == NusUuid.RX }
                    ?: throw BleTransportException(BleError.CharacteristicNotFound)
                requireProperty(tx, GattProperty.Write)
                requireProperty(rx, GattProperty.Notify)
                val cccd = rx.descriptors.singleOrNull { it.uuid == NusUuid.CCCD }
                    ?: throw BleTransportException(BleError.DescriptorNotFound, GattOperationKind.Subscribe)
                synchronized(lock) {
                    checkConnection(connection)
                    connection.tx = tx
                    connection.rx = rx
                }
                setPhase(connection, BlePhase.NegotiatingMtu)
                val mtu = perform(connection, deadline, GattOperationKind.Mtu) {
                    GattOperation.Mtu(it, configuration.requestedMtu)
                }
                if (mtu !is GattReply.Mtu) throw BleTransportException(BleError.InvalidResponse)
                if (mtu.actual !in 23..517) throw BleTransportException(BleError.InvalidMtu(mtu.actual))
                if (mtu.actual < configuration.minimumMtu) {
                    throw BleTransportException(BleError.MtuTooSmall(mtu.actual, configuration.minimumMtu))
                }
                synchronized(lock) {
                    checkConnection(connection)
                    connection.mtu = mtu.actual
                    connection.notificationsRequested = true
                }
                setPhase(connection, BlePhase.SubscribingToNotifications)
                perform(connection, deadline, GattOperationKind.Subscribe) {
                    GattOperation.Subscribe(it, rx, cccd)
                }
                setPhase(connection, BlePhase.DiscoveryComplete)
                setPhase(connection, BlePhase.Connected)
                connected = true
            } catch (failure: BleTransportException) {
                terminate(connection, failure)
                awaitConnectionClose(connection, failure)
                throw failure
            } catch (cancelled: CancellationException) {
                terminate(connection, cancelled)
                awaitConnectionClose(connection, cancelled)
                throw cancelled
            } finally {
                if (!connected && !connection.terminated) {
                    val aborted = BleTransportException(BleError.AbortedOperation(GattOperationKind.Connect))
                    terminate(connection, aborted)
                    awaitConnectionClose(connection, aborted)
                }
            }
        }
    }

    override suspend fun disconnect() {
        val detached = synchronized(lock) {
            intentRevision = Math.addExact(intentRevision, 1L)
            val connection = active
            connection?.let { terminateLocked(it, null) }
            ingress.markClosed(null)
            publish()
            Triple(connection, ingress, closing)
        }
        detached.first?.let(::beginClose)
        detached.second.finish()
        detached.third?.let { awaitClose(it, null) }
        currentCoroutineContext().ensureActive()
    }

    override suspend fun isConnected(): Boolean = synchronized(lock) { active?.phase == BlePhase.Connected }
    override suspend fun receivedData(): Flow<Bytes> = synchronized(lock) { ingress.flow }
    override suspend fun supportsWriteWithoutResponse(): Boolean =
        synchronized(lock) { active?.let { it.phase == BlePhase.Connected && writeCommands(it) } == true }

    override suspend fun supportsPipelinedReads(): Boolean = synchronized(lock) {
        active?.let {
            it.phase == BlePhase.Connected && writeCommands(it) && it.capabilities?.pipelinedReads == true
        } == true
    }

    fun updateFirmwareCapabilities(expectedGeneration: Long, capabilities: FirmwareFrameCapabilities) {
        synchronized(lock) {
            val connection = active ?: throw BleTransportException(BleError.NotConnected)
            if (connection.generation != expectedGeneration) {
                throw BleTransportException(BleError.StaleGeneration(expectedGeneration, connection.generation))
            }
            checkConnection(connection, connected = true)
            if (connection.capabilities != null && connection.capabilities != capabilities) {
                throw BleTransportException(BleError.FirmwareCapabilitiesAlreadyVerified)
            }
            connection.capabilities = capabilities
            publish()
        }
    }

    fun updateWritePacing(expectedGeneration: Long, delay: Duration) {
        require(delay.isFinite() && !delay.isNegative()) { "Invalid BLE write pacing" }
        synchronized(lock) {
            val connection = active ?: throw BleTransportException(BleError.NotConnected)
            if (connection.generation != expectedGeneration) {
                throw BleTransportException(BleError.StaleGeneration(expectedGeneration, connection.generation))
            }
            checkConnection(connection, connected = true)
            connection.writePacing = delay
        }
    }

    fun recordBondVerification(radioId: UUID, verifiedAt: Instant) {
        synchronized(lock) {
            bondEpochs[radioId] = Math.addExact(bondEpochs[radioId] ?: 0L, 1L)
            bondVerifications[radioId] = verifiedAt
        }
    }

    fun clearBondVerification(radioId: UUID) {
        synchronized(lock) {
            bondEpochs[radioId] = Math.addExact(bondEpochs[radioId] ?: 0L, 1L)
            bondVerifications.remove(radioId)
        }
    }

    fun bondVerification(radioId: UUID): Instant? = synchronized(lock) { bondVerifications[radioId] }

    fun setAppSessionLive(expectedGeneration: Long, radioId: UUID?) {
        synchronized(lock) {
            val connection = active ?: throw BleTransportException(BleError.NotConnected)
            if (expectedGeneration != connection.generation) {
                throw BleTransportException(BleError.StaleGeneration(expectedGeneration, connection.generation))
            }
            checkConnection(connection, connected = true)
            connection.liveRadioId = radioId
        }
    }

    fun setBondRefreshedHandler(handler: ((BondRefresh) -> Unit)?) {
        synchronized(lock) { bondRefreshed = handler }
    }

    fun isBondRefreshCurrent(refresh: BondRefresh): Boolean = synchronized(lock) {
        val connection = active
        connection?.phase == BlePhase.Connected &&
            connection.generation == refresh.generation && connection.liveRadioId == refresh.radioId &&
            bondEpochs[refresh.radioId] == refresh.verificationEpoch &&
            bondVerifications[refresh.radioId] == refresh.verifiedAt
    }

    suspend fun readRssi(): Int {
        val connection = synchronized(lock) {
            active?.also { checkConnection(it, connected = true) }
                ?: throw BleTransportException(BleError.NotConnected, GattOperationKind.Rssi)
        }
        val reply = operationMutex.withLock {
            synchronized(lock) { checkConnection(connection, connected = true) }
            performLocked(connection, clock.now + configuration.timeouts.write, GattOperationKind.Rssi) {
                GattOperation.Rssi(it)
            }
        }
        if (reply !is GattReply.Rssi) throw BleTransportException(BleError.InvalidResponse, GattOperationKind.Rssi)
        if (reply.status != 0) {
            throw BleTransportException(
                BleError.RssiReadFailed(reply.status), GattOperationKind.Rssi, reply.status,
                statusDomain = GattStatusDomain.RadioMeasurement,
            )
        }
        val refreshed = synchronized(lock) {
            checkConnection(connection, connected = true)
            val radioId = connection.liveRadioId
            if (radioId != null && bondVerifications.containsKey(radioId)) {
                val verifiedAt = wallClock.instant()
                bondVerifications[radioId] = verifiedAt
                BondRefresh(radioId, connection.generation, requireNotNull(bondEpochs[radioId]), verifiedAt) to bondRefreshed
            } else null
        }
        refreshed?.let { (value, handler) -> handler?.invoke(value) }
        return reply.dbm
    }

    override suspend fun send(data: Bytes) = send(data, requestWriteCommand = false)
    override suspend fun sendWithoutResponse(data: Bytes) = send(data, requestWriteCommand = true)

    private suspend fun send(data: Bytes, requestWriteCommand: Boolean) {
        currentCoroutineContext().ensureActive()
        val connection = synchronized(lock) {
            active?.also { checkConnection(it, connected = true) }
                ?: throw BleTransportException(BleError.NotConnected, GattOperationKind.Write)
        }
        operationMutex.withLock {
            val mode = synchronized(lock) {
                checkConnection(connection, connected = true)
                if (requestWriteCommand && writeCommands(connection)) GattWriteMode.WithoutResponse
                else GattWriteMode.WithResponse
            }
            if (mode == GattWriteMode.WithResponse) awaitWritePacing(connection)
            currentCoroutineContext().ensureActive()
            val tx = synchronized(lock) {
                checkConnection(connection, connected = true)
                val maximum = maximumCommandBytes(connection)
                if (data.size > maximum) {
                    val error = if (connection.capabilities == null && data.size > BOOTSTRAP_MAXIMUM) {
                        BleError.FirmwareCapabilityUnverified(data.size, BOOTSTRAP_MAXIMUM)
                    } else BleError.FrameTooLarge(data.size, maximum)
                    throw BleTransportException(error, GattOperationKind.Write)
                }
                connection.tx ?: throw BleTransportException(BleError.CharacteristicNotFound)
            }
            performLocked(connection, clock.now + configuration.timeouts.write, GattOperationKind.Write) {
                GattOperation.Write(it, tx, data, mode)
            }
            synchronized(lock) {
                checkConnection(connection, connected = true)
            }
        }
    }

    private suspend fun awaitWritePacing(connection: Connection) {
        val deadline = synchronized(lock) {
            checkConnection(connection, connected = true)
            if (connection.writePacing == Duration.ZERO) return
            connection.earliestNextWrite
        }
        if (deadline <= clock.now) return
        coroutineScope {
            val sleeper = async(start = CoroutineStart.UNDISPATCHED) { clock.sleepUntil(deadline) }
            try {
                select {
                    connection.termination.onAwait {
                        synchronized(lock) { checkConnection(connection, connected = true) }
                    }
                    sleeper.onAwait { }
                }
                synchronized(lock) { checkConnection(connection, connected = true) }
            } finally {
                withContext(NonCancellable) { sleeper.cancelAndJoin() }
            }
        }
    }

    private suspend fun perform(
        connection: Connection,
        deadline: Duration,
        kind: GattOperationKind,
        operation: (GattOperationKey) -> GattOperation,
    ): GattReply = operationMutex.withLock { performLocked(connection, deadline, kind, operation) }

    private suspend fun performLocked(
        connection: Connection,
        deadline: Duration,
        kind: GattOperationKind,
        operation: (GattOperationKey) -> GattOperation,
    ): GattReply = coroutineScope {
        currentCoroutineContext().ensureActive()
        val pending = synchronized(lock) {
            checkConnection(connection)
            check(connection.pending == null) { "GATT operation serialization violated" }
            sequence = Math.addExact(sequence, 1L)
            Pending(operation(GattOperationKey(connection.generation, sequence))).also { connection.pending = it }
        }
        val timeoutError = BleTransportException(
            if (kind in setOf(GattOperationKind.Write, GattOperationKind.Rssi)) BleError.OperationTimeout else BleError.ConnectionTimeout,
            kind,
        )
        val timer = launch(start = CoroutineStart.UNDISPATCHED) {
            clock.sleepUntil(deadline)
            pending.result.completeExceptionally(timeoutError)
        }
        var submissionAttempted = false
        var completed = false
        try {
            currentCoroutineContext().ensureActive()
            if (!pending.result.isCompleted) {
                // Entering the facade may issue work even if submission subsequently throws.
                submissionAttempted = true
                connection.gatt.submit(pending.operation)
            }
            val completion = pending.result.await()
            completed = true
            completion.acknowledgedWriteDeadline?.let { nextWrite ->
                synchronized(lock) {
                    checkConnection(connection, connected = true)
                    connection.earliestNextWrite = nextWrite
                }
            }
            completion.reply
        } catch (failure: BleTransportException) {
            if (submissionAttempted) {
                terminate(connection, failure)
                awaitConnectionClose(connection, failure)
            }
            throw failure
        } catch (cancelled: CancellationException) {
            if (submissionAttempted) {
                terminate(connection, cancelled)
                awaitConnectionClose(connection, cancelled)
            }
            throw cancelled
        } finally {
            withContext(NonCancellable) { timer.cancelAndJoin() }
            synchronized(lock) {
                if (connection.pending === pending) connection.pending = null
            }
            if (!completed && submissionAttempted && !connection.terminated) {
                val aborted = BleTransportException(BleError.AbortedOperation(kind), kind)
                terminate(connection, aborted)
                awaitConnectionClose(connection, aborted)
            }
        }
    }

    private val events = object : GattEvents {
        override fun onReply(connection: GattConnection, key: GattOperationKey, reply: GattReply) {
            val (pending, completion) = synchronized(lock) {
                val current = callbackConnection(connection, key) ?: return
                val pending = current.pending ?: return reject(RejectedCallback.Operation)
                if (pending.operation.key != key) return reject(RejectedCallback.Operation)
                if (pending.operation.kind != reply.kind) return reject(RejectedCallback.Kind)
                val write = pending.operation as? GattOperation.Write
                val deadline = if (write?.mode == GattWriteMode.WithResponse) {
                    clock.now + current.writePacing
                } else null
                pending to Completion(reply, deadline)
            }
            if (!pending.result.complete(completion)) {
                synchronized(lock) { reject(RejectedCallback.Duplicate) }
            }
        }

        override fun onFailure(connection: GattConnection, key: GattOperationKey, failure: BleTransportException) {
            handleCallback(connection, key) { current ->
                if (current.pending?.operation?.key != key) return@handleCallback reject(RejectedCallback.Operation)
                terminateLocked(current, failure)
            }
        }

        override fun onNotification(connection: GattConnection, characteristic: GattCharacteristic, data: Bytes) {
            handleCallback(connection) { current ->
                if (current.rx !== characteristic || !current.notificationsRequested) {
                    return@handleCallback reject(RejectedCallback.Attribute)
                }
                if (current.ingress.packets.trySend(data).isFailure) {
                    terminateLocked(current, BleTransportException(BleError.NotificationDeliveryFailed))
                }
            }
        }

        override fun onDisconnected(connection: GattConnection, status: Int) {
            handleCallback(connection) { current ->
                val failure = when {
                    status != 0 -> connectionStateFailure(current.pending?.operation?.kind ?: GattOperationKind.Connect, status)
                    current.phase == BlePhase.Connected -> null
                    else -> BleTransportException(BleError.ConnectionFailed("gatt.disconnectedDuringSetup"))
                }
                terminateLocked(current, failure)
            }
        }

        override fun onUnavailable(connection: GattConnection, availability: BluetoothAvailability) {
            handleCallback(connection) { current ->
                this@BleTransport.availability = availability
                availabilityFailure(availability)?.let { terminateLocked(current, it) }
                publish()
            }
        }

        override fun onBondChanged(connection: GattConnection, bond: BondState) {
            handleCallback(connection) { current ->
                val previous = current.bond
                current.bond = bond
                if (bond == BondState.None && previous in setOf(BondState.Bonded, BondState.Bonding)) {
                    terminateLocked(current, BleTransportException(BleError.AuthenticationFailed))
                }
                publish()
            }
        }

        override fun onLinkFailure(connection: GattConnection, failure: BleTransportException) {
            handleCallback(connection) { current ->
                terminateLocked(current, failure)
            }
        }

        private fun handleCallback(
            connection: GattConnection,
            key: GattOperationKey? = null,
            action: (Connection) -> Unit,
        ) {
            val current = synchronized(lock) {
                val current = callbackConnection(connection, key) ?: return
                action(current)
                current
            }
            if (current.terminated) beginClose(current)
        }

        override fun onRejectedCallback(connection: GattConnection, reason: RejectedCallback) {
            synchronized(lock) { reject(reason) }
        }
    }

    private fun callbackConnection(connection: GattConnection, key: GattOperationKey? = null): Connection? {
        val current = active
        if (current == null || current.gatt !== connection || current.terminated) {
            reject(RejectedCallback.Connection)
            return null
        }
        if (key != null && key.generation != current.generation) {
            reject(RejectedCallback.Operation)
            return null
        }
        return current
    }

    private fun reject(reason: RejectedCallback) {
        rejectedCallbacks = Math.addExact(rejectedCallbacks, 1L)
        lastRejected = reason
        publish()
    }

    private fun checkConnection(connection: Connection, connected: Boolean = false) {
        if (active !== connection || connection.terminated || (connected && connection.phase != BlePhase.Connected)) {
            when (val cause = connection.terminalCause) {
                is BleTransportException -> throw cause
                else -> throw BleTransportException(BleError.NotConnected)
            }
        }
    }

    private fun setPhase(connection: Connection, phase: BlePhase) {
        synchronized(lock) {
            checkConnection(connection)
            connection.phase = phase
            publish()
        }
    }

    private fun terminate(connection: Connection, cause: Throwable?) {
        synchronized(lock) { terminateLocked(connection, cause) }
        beginClose(connection)
    }

    private fun terminateLocked(connection: Connection, cause: Throwable?) {
        if (connection.terminated) return
        connection.terminated = true
        connection.terminalCause = cause
        connection.ingress.markClosed(cause)
        connection.phase = BlePhase.Disconnecting
        connection.liveRadioId = null
        if (active === connection) {
            active = null
            issue = (cause as? BleTransportException)?.error
        }
        val receipt = CompletableDeferred<Unit>()
        connection.closeReceipt = receipt
        closing = receipt
        publish()
    }

    private fun beginClose(connection: Connection) {
        val barrier = synchronized(lock) {
            if (connection.closeStarted) return
            connection.closeStarted = true
            requireNotNull(connection.closeReceipt)
        }
        connection.termination.complete(Unit)
        connection.ingress.finish()
        connection.pending?.result?.completeExceptionally(
            connection.terminalCause ?: BleTransportException(BleError.NotConnected),
        )
        val receipt = connection.gatt.close()
        receipt.invokeOnCompletion { failure ->
            if (failure == null) barrier.complete(Unit) else barrier.completeExceptionally(failure)
            synchronized(lock) {
                if (active == null && closing === barrier) {
                    if (failure != null) issue = when (failure) {
                        is BleTransportException -> failure.error
                        else -> BleError.CleanupFailed("gatt.closeCompletion")
                    }
                }
                publish()
            }
        }
    }

    private suspend fun awaitConnectionClose(connection: Connection, primary: Throwable?) {
        synchronized(lock) { connection.closeReceipt }?.let { awaitClose(it, primary) }
    }

    private suspend fun awaitClose(receipt: Deferred<Unit>, primary: Throwable?) {
        withContext(NonCancellable) {
            try {
                receipt.await()
            } catch (failure: BleTransportException) {
                if (primary == null) throw failure
                if (primary !== failure && failure !in primary.suppressed) primary.addSuppressed(failure)
            }
        }
    }

    private fun snapshot(): BleLinkDiagnostics {
        val current = active
        return BleLinkDiagnostics(
            availability, current?.phase ?: if (closing?.isCompleted == false) BlePhase.Disconnecting else BlePhase.Idle, generation,
            if (current == null) null else facade.handle, current?.bond, current?.mtu,
            current?.mtu?.let { maximumCommandBytes(current) }, current?.capabilities != null,
            current?.let(::writeCommands) == true, current?.let { writeCommands(it) && it.capabilities?.pipelinedReads == true } == true,
            rejectedCallbacks, lastRejected, issue,
        )
    }

    private fun publish() { mutableDiagnostics.value = snapshot() }

    private fun maximumCommandBytes(connection: Connection): Int =
        minOf((connection.mtu ?: 23) - 3, 512, connection.capabilities?.maximumCommandBytes ?: BOOTSTRAP_MAXIMUM)

    private fun writeCommands(connection: Connection): Boolean =
        connection.capabilities?.writeWithoutResponse == true &&
            connection.tx?.properties?.contains(GattProperty.WriteWithoutResponse) == true

    private fun requireProperty(characteristic: GattCharacteristic, property: GattProperty) {
        if (property !in characteristic.properties) {
            throw BleTransportException(BleError.CharacteristicPropertyMissing(property))
        }
    }

    private fun availabilityFailure(value: BluetoothAvailability): BleTransportException? = when (value) {
        BluetoothAvailability.Ready -> null
        BluetoothAvailability.PoweredOff -> BleTransportException(BleError.BluetoothPoweredOff)
        BluetoothAvailability.Unauthorized -> BleTransportException(BleError.BluetoothUnauthorized)
        BluetoothAvailability.Unavailable -> BleTransportException(BleError.BluetoothUnavailable)
    }

    companion object {
        const val BOOTSTRAP_MAXIMUM = 20
    }
}
