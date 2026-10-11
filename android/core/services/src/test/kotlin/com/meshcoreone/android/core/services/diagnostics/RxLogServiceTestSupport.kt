// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceReprocessTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceRegionReprocessTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: JVM fakes standing in for the source's in-memory PersistenceStore, MockPersistenceStore and MockTransport session.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.contracts.domain.ChannelRegionUpdate
import com.meshcoreone.android.core.contracts.domain.DirectRegionUpdate
import com.meshcoreone.android.core.contracts.domain.RxLogDecryptionUpdate
import com.meshcoreone.android.core.contracts.domain.RxLogRegionUpdate
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotMap
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.WireCrypto
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.parser.TransportCodeRegionResolver
import com.meshcoreone.android.core.protocol.session.SessionEventStreaming
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeout

internal data class RxLogSvcHopCall(val publicKey: Bytes, val hopCount: Long, val advertTimestamp: UInt?)

/** Message row the fake store correlates exactly like the Room repository's region batch updates. */
internal data class RxLogSvcMessage(
    val id: UUID = UUID.randomUUID(),
    val channelIndex: UByte?,
    val senderTimestamp: UInt,
    val senderKeyPrefix: Bytes? = null,
    val incoming: Boolean = true,
    val regionScope: String? = null,
    val regionScopeMatches: List<String> = emptyList(),
)

/** Thread-safe in-memory store mirroring the merged repository semantics the service depends on. */
internal class RxLogSvcFakeStore : RxLogServiceStore {
    private val lock = Any()
    private val entries = LinkedHashMap<UUID, RxLogEntryDTO>()
    private val messages = LinkedHashMap<UUID, RxLogSvcMessage>()
    private val hopCalls = mutableListOf<RxLogSvcHopCall>()
    private val transportFetchLimits = mutableListOf<Long>()
    private var channelRows: List<ChannelDTO> = emptyList()
    private var contactKeys: Map<UByte, List<Bytes>> = emptyMap()
    private var devices: Map<RadioId, DeviceDTO> = emptyMap()
    private var decryptStatusFetches = 0
    private var deviceFetches = 0

    @Volatile var saveFailure: Exception? = null
    @Volatile var transportFetchFailure: Exception? = null
    @Volatile var beforeTransportFetch: suspend () -> Unit = {}
    @Volatile var beforeDecryptStatusFetch: suspend () -> Unit = {}
    @Volatile var beforeDeviceFetch: suspend () -> Unit = {}

    val inboundHopCountCalls: List<RxLogSvcHopCall> get() = synchronized(lock) { hopCalls.toList() }
    val transportCodeFetchLimits: List<Long> get() = synchronized(lock) { transportFetchLimits.toList() }
    val decryptStatusFetchCount: Int get() = synchronized(lock) { decryptStatusFetches }
    val deviceFetchCount: Int get() = synchronized(lock) { deviceFetches }

    fun saveChannel(channel: ChannelDTO) = synchronized(lock) {
        channelRows = (channelRows.filterNot { it.index == channel.index } + channel).sortedBy { it.index }
    }
    fun saveDevice(device: DeviceDTO) = synchronized(lock) { devices = devices + (device.radioId to device) }
    fun saveContactKeys(keys: Map<UByte, List<Bytes>>) = synchronized(lock) { contactKeys = keys.toMap() }
    fun saveMessage(message: RxLogSvcMessage) = synchronized(lock) { messages[message.id] = message }
    fun message(id: UUID): RxLogSvcMessage? = synchronized(lock) { messages[id] }
    fun entry(id: UUID): RxLogEntryDTO? = synchronized(lock) { entries[id] }
    fun allEntries(): List<RxLogEntryDTO> = synchronized(lock) { entries.values.toList() }
    fun seedEntry(entry: RxLogEntryDTO) = synchronized(lock) { entries[entry.id] = entry }

    override suspend fun fetchChannels(radioId: RadioId) =
        synchronized(lock) { channelRows.filter { it.radioId == radioId }.snapshot() }
    override suspend fun fetchContactPublicKeysByPrefix(radioId: RadioId): SnapshotMap<UByte, SnapshotList<Bytes>> =
        synchronized(lock) { contactKeys.mapValues { it.value.snapshot() }.snapshotMap() }
    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? {
        synchronized(lock) { deviceFetches += 1 }
        beforeDeviceFetch()
        return synchronized(lock) { devices[radioId] }
    }
    override suspend fun setInboundHopCount(radioId: RadioId, publicKey: Bytes, hopCount: Long, advertTimestamp: UInt?) {
        synchronized(lock) { hopCalls += RxLogSvcHopCall(publicKey, hopCount, advertTimestamp) }
    }
    override suspend fun saveRxLogEntry(dto: RxLogEntryDTO) {
        saveFailure?.let { throw it }
        synchronized(lock) { entries[dto.id] = dto }
    }
    override suspend fun fetchRxLogEntries(radioId: RadioId, limit: Long) = synchronized(lock) {
        entries.values.filter { it.radioId == radioId }.sortedByDescending { it.receivedAt }.take(limit.toInt()).snapshot()
    }
    override suspend fun clearRxLogEntries(radioId: RadioId) {
        synchronized(lock) { entries.values.removeAll { it.radioId == radioId } }
    }
    override suspend fun fetchRecentEntriesByDecryptStatus(radioId: RadioId, status: DecryptStatus, since: Instant):
        SnapshotList<RxLogEntryDTO> {
        synchronized(lock) { decryptStatusFetches += 1 }
        beforeDecryptStatusFetch()
        return synchronized(lock) {
            entries.values.filter { it.radioId == radioId && it.decryptStatus == status && it.receivedAt >= since }
                .sortedBy { it.receivedAt }.snapshot()
        }
    }
    override suspend fun batchUpdateRxLogDecryption(radioId: RadioId, updates: SnapshotList<RxLogDecryptionUpdate>) {
        synchronized(lock) {
            for (update in updates) {
                val row = entries[update.id]?.takeIf { it.radioId == radioId } ?: continue
                entries[update.id] = row.copy(
                    channelIndex = update.channelIndex, channelName = update.channelName,
                    decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = update.senderTimestamp,
                )
            }
        }
    }
    override suspend fun fetchEntriesWithTransportCode(radioId: RadioId, limit: Long): SnapshotList<RxLogEntryDTO> {
        synchronized(lock) { transportFetchLimits += limit }
        beforeTransportFetch()
        transportFetchFailure?.let { throw it }
        return synchronized(lock) {
            entries.values.filter { it.radioId == radioId && it.transportCode != null }
                .sortedByDescending { it.receivedAt }.take(limit.toInt()).snapshot()
        }
    }
    override suspend fun batchUpdateRxLogRegion(radioId: RadioId, updates: SnapshotList<RxLogRegionUpdate>) {
        synchronized(lock) {
            for (update in updates) {
                val row = entries[update.id]?.takeIf { it.radioId == radioId } ?: continue
                entries[update.id] = row.copy(regionScope = update.regionScope, regionScopeMatches = update.regionScopeMatches)
            }
        }
    }
    override suspend fun batchUpdateChannelMessageRegion(radioId: RadioId, updates: SnapshotList<ChannelRegionUpdate>) =
        synchronized(lock) {
            updates.flatMap { update ->
                rewriteMessages(update.regionScope, update.regionScopeMatches) {
                    it.channelIndex == update.channelIndex && it.senderTimestamp == update.senderTimestamp
                }
            }.snapshot()
        }
    override suspend fun batchUpdateDMMessageRegion(radioId: RadioId, updates: SnapshotList<DirectRegionUpdate>) =
        synchronized(lock) {
            updates.flatMap { update ->
                rewriteMessages(update.regionScope, update.regionScopeMatches) {
                    it.channelIndex == null && it.senderTimestamp == update.senderTimestamp &&
                        it.senderKeyPrefix?.takeIf { prefix -> !prefix.isEmpty }?.get(0) == update.senderPrefixByte
                }
            }.snapshot()
        }

    private fun rewriteMessages(scope: String?, matches: List<String>, predicate: (RxLogSvcMessage) -> Boolean): List<UUID> =
        messages.values.filter { it.incoming && predicate(it) }.map { row ->
            messages[row.id] = row.copy(regionScope = scope, regionScopeMatches = matches.toList())
            row.id
        }
}

/** Session whose `events(filter)` registers synchronously, like the real `SessionCore.eventsTracked`. */
internal class RxLogSvcFakeSession : SessionEventStreaming {
    private val broadcaster = RxLogStreamBroadcaster<MeshEvent>()
    private val registrations = AtomicLong()
    val subscriberCount: Int get() = broadcaster.subscriberCount
    val registrationCount: Long get() = registrations.get()
    override val connectionState: Flow<ConnectionState> = emptyFlow()
    override fun events(): Flow<MeshEvent> = broadcaster.subscribe().also { registrations.incrementAndGet() }
    override fun events(filter: EventFilter): Flow<MeshEvent> = events().filter(filter::matches)
    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? = null
    fun emit(event: MeshEvent) = broadcaster.yield(event)
}

internal data class RxLogSvcLogLine(val level: DebugLogLevel, val category: String, val message: String)

internal class RxLogSvcLogRecorder : RxLogDiagnostics {
    private val lines = mutableListOf<RxLogSvcLogLine>()
    val all: List<RxLogSvcLogLine> get() = synchronized(lines) { lines.toList() }
    override fun log(level: DebugLogLevel, category: String, message: String) {
        synchronized(lines) { lines += RxLogSvcLogLine(level, category, message) }
    }
}

/** One service graph per test case; closing it stops monitoring and cancels the injected scope. */
internal class RxLogSvcHarness(
    val store: RxLogSvcFakeStore = RxLogSvcFakeStore(),
    heardRepeats: RxLogRepeatProcessing? = null,
    clock: Clock = Clock.systemUTC(),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    val session = RxLogSvcFakeSession()
    val logs = RxLogSvcLogRecorder()
    val service = RxLogService(session, store, heardRepeats, scope, clock, logs, Locale.US)

    override fun close() {
        service.stopEventMonitoring()
        scope.cancel()
    }
}

internal fun rxLogSvcRadio(): RadioId = RadioId(UUID.randomUUID())

internal fun rxLogSvcDevice(radioId: RadioId, knownRegions: List<String>) = DeviceDTO(
    radioId = radioId, publicKey = Bytes(ByteArray(32)), nodeName = "Test Device", knownRegions = knownRegions.snapshot(),
)

internal fun rxLogSvcChannel(radioId: RadioId, index: Int, name: String, secret: Bytes) =
    ChannelDTO(radioId = radioId, index = index.toUByte(), name = name, secret = secret)

internal fun rxLogSvcParsed(
    payloadType: PayloadType,
    packetPayload: Bytes,
    routeType: RouteType = RouteType.FLOOD,
    pathLength: UByte = 0u,
    payloadTypeBits: UByte = 0u,
    transportCode: Bytes? = null,
    snr: Double = 8.0,
    rssi: Long = -70,
    senderPubkeyPrefix: Bytes? = null,
) = ParsedRxLogData(
    snr, rssi, packetPayload, routeType, payloadType, 0u, payloadTypeBits, transportCode, pathLength,
    emptyList<UByte>(), packetPayload, senderPubkeyPrefix,
)

/** Little-endian `transport_codes[0]` for [payload] under the named region's scope key. */
internal fun rxLogSvcTransportCode(regionName: String, payloadTypeBits: UByte, payload: Bytes): Bytes {
    val scopeKey = checkNotNull(TransportCodeRegionResolver.deriveScopeKey(regionName))
    val code = TransportCodeRegionResolver.calcTransportCode(scopeKey, payloadTypeBits, payload).toInt()
    return Bytes.of(code and 0xFF, (code shr 8) and 0xFF)
}

internal fun rxLogSvcScopeKey(regionName: String): Bytes = checkNotNull(TransportCodeRegionResolver.deriveScopeKey(regionName))

internal fun rxLogSvcUInt32LE(value: UInt): Bytes =
    Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())

/**
 * Wire channel payload `[channelHash:1][MAC:2][ciphertext:N]` that `ChannelCrypto.decrypt` accepts:
 * zero-padded AES-128 ECB ciphertext authenticated with a truncated HMAC-SHA256 (encrypt-then-MAC).
 */
internal fun rxLogSvcEncryptedChannelPayload(timestamp: UInt, text: String, secret: Bytes): Bytes {
    val plaintext = rxLogSvcUInt32LE(timestamp) + Bytes.of(0) + Bytes.utf8(text)
    val ciphertext = WireCrypto.encryptAes128EcbZeroPadded(plaintext, secret)
    return Bytes.of(0x00) + WireCrypto.truncatedHmacSha256(ciphertext, secret) + ciphertext
}

/** Polls [condition] until true, failing with [description] after [timeout] (source `waitUntil`). */
internal suspend fun rxLogSvcWaitUntil(description: String, timeout: Duration = 5.seconds, condition: suspend () -> Boolean) {
    try {
        withTimeout(timeout) { while (!condition()) delay(5.milliseconds) }
    } catch (timeoutFailure: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("Timed out waiting: $description", timeoutFailure)
    }
}
