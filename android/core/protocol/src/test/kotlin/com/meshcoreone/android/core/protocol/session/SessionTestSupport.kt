// AndroidOnly: WP-107 Deterministic real-session driver using the merged MockTransport and pinned Swift wire layouts.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DynamicTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal val testEpoch: Instant = Instant.ofEpochSecond(1_704_067_200)
internal fun hex(text: String): Bytes = Bytes.fromHex(text.replace(" ", ""))
internal fun filled(byte: Int, size: Int): Bytes = Bytes(ByteArray(size) { byte.toByte() })
internal fun little32(value: Long): Bytes = Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())
internal fun little16(value: Int): Bytes = Bytes(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
internal fun raw(code: Int, payload: Bytes = Bytes.EMPTY): Bytes = Bytes.of(code) + payload

@OptIn(ExperimentalCoroutinesApi::class)
internal class SchedulerClock(private val scheduler: TestCoroutineScheduler) : SessionClock {
    override val now: Duration get() = scheduler.currentTime.milliseconds
    override val wallClock: Clock = Clock.fixed(testEpoch, ZoneOffset.UTC)
    override suspend fun sleepFor(duration: Duration) = delay(duration)
}

internal class SessionRadioTransport(
    var writeWithoutResponse: Boolean = false,
    var pipelinedReads: Boolean = writeWithoutResponse,
) : MeshTransport {
    val mock = MockTransport()
    val sent: List<Bytes> get() = mock.sentData
    var connects = 0
        private set
    var disconnects = 0
        private set
    var acknowledgedWrites = 0
        private set
    var unacknowledgedWrites = 0
        private set
    var activeCollectors = 0
        private set
    var maximumCollectors = 0
        private set
    var onSend: suspend (Bytes) -> Unit = {}
    var beforeSend: suspend (Bytes) -> Unit = {}
    var beforeConnect: suspend () -> Unit = {}
    var beforeDisconnect: suspend () -> Unit = {}
    var onReceive: (Bytes) -> Unit = {}

    override suspend fun connect() { connects += 1; beforeConnect(); mock.connect() }
    override suspend fun disconnect() { disconnects += 1; beforeDisconnect(); mock.disconnect() }
    override suspend fun isConnected(): Boolean = mock.isConnected()
    override suspend fun supportsWriteWithoutResponse(): Boolean = writeWithoutResponse
    override suspend fun supportsPipelinedReads(): Boolean = pipelinedReads
    override suspend fun send(data: Bytes) { beforeSend(data); mock.send(data); acknowledgedWrites += 1; onSend(data) }
    override suspend fun sendWithoutResponse(data: Bytes) {
        check(writeWithoutResponse) { "The test radio did not advertise unacknowledged writes" }
        beforeSend(data); mock.send(data); unacknowledgedWrites += 1; onSend(data)
    }
    override suspend fun receivedData(): Flow<Bytes> {
        val stream = mock.receivedData()
        return flow {
            activeCollectors += 1
            maximumCollectors = maxOf(maximumCollectors, activeCollectors)
            try { stream.collect { onReceive(it); emit(it) } } finally { activeCollectors -= 1 }
        }
    }
    suspend fun receive(data: Bytes) = mock.simulateReceive(data)
    suspend fun ok(value: UInt? = null) = mock.simulateOK(value)
    suspend fun error(code: Int) = mock.simulateError(code.toUByte())
}

internal class SessionFixture(
    val transport: SessionRadioTransport,
    val session: MeshCoreSession,
    val diagnostics: MutableList<SessionDiagnostic>,
)

internal class CheckedRequest<T>(private val task: Deferred<Result<T>>) {
    val isCompleted: Boolean get() = task.isCompleted
    suspend fun await(): T = task.await().getOrThrow()
    fun cancel() = task.cancel()
}

internal fun <T> TestScope.checkedRequest(operation: suspend () -> T): CheckedRequest<T> =
    CheckedRequest(backgroundScope.async {
        try {
            Result.success(operation())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // backgroundScope reports uncaught failures at test end; retain them for the actual typed assertion.
            Result.failure(failure)
        }
    })

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.fixture(
    configuration: SessionConfiguration = SessionConfiguration(
        defaultTimeout = 1.0, clientIdentifier = "MCore", binaryRequestOverallTimeout = 4.0,
        binaryRequestRetransmitInterval = null, contactStreamInactivityTimeout = 0.1,
        contactStreamHardTimeout = 3.0, channelPipelineIdleTimeout = 0.1,
        channelPipelineHardTimeout = 3.0, channelPipelinePostDrainGrace = 0.02,
    ),
    transport: SessionRadioTransport = SessionRadioTransport(),
    randomTag: () -> UInt = { 0xaabbccddu },
    owningJob: Job? = null,
): SessionFixture {
    val diagnostics = mutableListOf<SessionDiagnostic>()
    val context = if (owningJob == null) backgroundScope.coroutineContext else backgroundScope.coroutineContext + owningJob
    val session = MeshCoreSession(transport, configuration, SchedulerClock(testScheduler), context, randomTag, diagnostics::add)
    return SessionFixture(transport, session, diagnostics)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.start(
    fixture: SessionFixture, self: Bytes = selfPacket(),
    expectedAppStart: Bytes = hex("01032020202020204d436f7265"),
) {
    val task = backgroundScope.async { fixture.session.start() }
    runCurrent()
    assertEquals(expectedAppStart, fixture.transport.sent.last())
    fixture.transport.receive(self)
    runCurrent()
    task.await()
    assertNotNull(fixture.session.currentSelfInfo)
    assertTrue(fixture.transport.isConnected())
    assertEquals(1, fixture.transport.activeCollectors)
}

internal suspend fun <T> command(
    fixture: SessionFixture, expected: Bytes, response: Bytes, operation: suspend () -> T,
): T {
    var observed = false
    fixture.transport.onSend = { frame ->
        assertEquals(expected, frame)
        observed = true
        fixture.transport.receive(response)
    }
    val result = operation()
    assertTrue(observed, "The real session must write the expected frame")
    fixture.transport.onSend = {}
    return result
}

internal fun selfPacket(
    key: Bytes = filled(1, 32), name: String = "Test",
    manual: Boolean = false, multiAcks: Int = 0, telemetry: Int = 0, policy: Int = 0,
): Bytes = raw(0x05, Bytes.of(1, 22, 22) + key + little32(0) + little32(0) +
    Bytes.of(multiAcks, policy, telemetry, if (manual) 1 else 0) +
    little32(915_000) + little32(125_000) + Bytes.of(7, 5) + Bytes.utf8(name))

internal fun batteryPacket(level: Int): Bytes = raw(0x0c, little16(level))
internal fun sentPacket(tag: Bytes = hex("aabbccdd"), timeoutMs: Long = 5000): Bytes = raw(0x06, Bytes.of(0) + tag + little32(timeoutMs))
internal fun channelPacket(index: Int, name: String = "channel", secret: Int = 0): Bytes =
    raw(0x12, Bytes.of(index) + Bytes.utf8(name).paddedOrTruncated(32) + filled(secret, 16))
internal fun contactPacket(key: Bytes = filled(0x11, 32), name: String = "Node", type: Int = 1, pathLength: Int = 0xff, path: Bytes = Bytes.EMPTY): Bytes =
    raw(0x03, key + Bytes.of(type, 0, pathLength) + path.paddedOrTruncated(64) +
        Bytes.utf8(name).paddedOrTruncated(32) + little32(0) + little32(0) + little32(0) + little32(0))
internal fun contactsStart(count: Long): Bytes = raw(0x02, little32(count))
internal fun contactsEnd(modified: Long = 1_704_067_200): Bytes = raw(0x04, little32(modified))
internal fun binaryPacket(tag: Bytes, payload: Bytes = Bytes.EMPTY): Bytes = raw(0x8c, Bytes.of(0) + tag + payload)
internal fun ackPacket(code: Bytes, tripTime: Long? = null): Bytes = raw(0x82, code + (tripTime?.let(::little32) ?: Bytes.EMPTY))
internal fun telemetryPacket(prefix: Bytes, payload: Bytes = Bytes.EMPTY): Bytes = raw(0x8b, Bytes.of(0) + prefix + payload)
internal fun statusPacket(key: Bytes, battery: Int = 1000, posted: Int = 0, pushed: Int = 0): Bytes =
    raw(0x87, Bytes.of(0) + key.prefix(6) + little16(battery) + little16(0) + little16(-110) + little16(-85) +
        little32(100) + little32(50) + little32(25) + little32(3600) + little32(5) + little32(10) +
        little32(15) + little32(20) + little16(0) + little16(0) + little16(0) + little16(0) + little16(posted) + little16(pushed))

internal fun original(suite: String, name: String, assertions: suspend TestScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("$suite::$name()") { runTest { assertions() } }
internal fun nativeCase(name: String, assertions: suspend TestScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-107::$name") { runTest { assertions() } }
