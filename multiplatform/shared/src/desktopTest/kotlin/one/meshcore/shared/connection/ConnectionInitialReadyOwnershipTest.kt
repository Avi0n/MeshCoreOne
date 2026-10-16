package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionInitialReadyFixture.Companion.DEVICE
import one.meshcore.shared.connection.ConnectionInitialReadyFixture.Companion.DISCONNECT_NAME
import one.meshcore.shared.connection.ConnectionInitialReadyFixture.Companion.TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionInitialReadyOwnershipTest {
    @Test
    fun explicitDisconnectCannotPublishInitialReadyWhileCaptureIsQueued() {
        val fixture = ConnectionInitialReadyFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    val states = mutableListOf<ConnectionState>()
                    val collector = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        fixture.manager.state.collect { states += it }
                    }.also { fixture.callers += it }
                    val connect = fixture.scope.async { fixture.manager.connect(DEVICE) }
                        .also { fixture.callers += it }
                    fixture.assertActiveOwner(connect, fixture.session.completed.await())
                    fixture.seed()
                    val container = requireNotNull(fixture.manager.services)
                    val generation = fixture.manager.connectGeneration
                    val radio = requireNotNull(fixture.manager.radioId)
                    val rows = fixture.rows()
                    val devices = fixture.devices()
                    assertEquals(1, fixture.session.autoFetchStartCount)
                    assertEquals(0, fixture.session.getTimeCount)
                    assertFalse(container.sendQueue.isRetired)
                    fixture.queries.armed.set(true)
                    val disconnect = fixture.scope.async(CoroutineName(DISCONNECT_NAME)) {
                        assertTrue(currentCoroutineContext().job.isActive)
                        fixture.manager.disconnect()
                    }.also { fixture.callers += it }
                    val work = fixture.queries.entered.await()
                    assertTrue(disconnect.isActive)
                    assertTrue(work.context.job.isActive)
                    assertEquals(DISCONNECT_NAME, work.context[CoroutineName]?.name)
                    assertTrue(work.frames.any { it.methodName == "getAll" })
                    assertFalse(work.executed.get())
                    assertFalse(fixture.manager.wantsConnection)
                    assertNull(fixture.manager.currentDeviceId)
                    assertNull(fixture.manager.currentTransportType)
                    assertEquals(generation, fixture.manager.connectGeneration)
                    assertSame(container, fixture.manager.services)
                    assertSame(fixture.session, fixture.manager.session)
                    assertSame(fixture.link, fixture.manager.link)
                    fixture.assertActiveOwner(connect, fixture.session.completed.await())
                    val boundary = states.size
                    fixture.session.release.complete(Unit)
                    connect.await()
                    fixture.barrier()
                    assertTrue(collector.isActive)
                    assertTrue(disconnect.isActive)
                    assertFalse(work.executed.get())
                    assertFalse(fixture.manager.wantsConnection)
                    assertEquals(generation, fixture.manager.connectGeneration)
                    assertEquals(radio, fixture.manager.radioId)
                    assertSame(container, fixture.manager.services)
                    assertSame(fixture.session, fixture.manager.session)
                    assertSame(fixture.link, fixture.manager.link)
                    assertFalse(container.sendQueue.isRetired)
                    assertFalse(fixture.session.closed)
                    assertEquals(rows, fixture.rows())
                    assertEquals(devices, fixture.devices())
                    fixture.assertNoOutgoing()
                    assertEquals(1, fixture.session.autoFetchStartCount)
                    val observed = states.drop(boundary) + fixture.manager.state.value
                    assertFalse(
                        observed.any { it is ConnectionState.Ready },
                        "Late Ready after wanted intent cleared: $observed",
                    )
                    fixture.queries.release()
                    work.returned.await()
                    disconnect.await()
                    fixture.assertDisconnected()
                }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun liveInitialConnectStillPromotesReadyAfterCompletedAutoFetchReturn() {
        val fixture = ConnectionInitialReadyFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(TIMEOUT_MS) {
                    val connect = fixture.scope.async { fixture.manager.connect(DEVICE) }
                        .also { fixture.callers += it }
                    fixture.assertActiveOwner(connect, fixture.session.completed.await())
                    fixture.seed()
                    val container = requireNotNull(fixture.manager.services)
                    val generation = fixture.manager.connectGeneration
                    val radio = requireNotNull(fixture.manager.radioId)
                    val rows = fixture.rows()
                    val devices = fixture.devices()
                    assertTrue(fixture.manager.wantsConnection)
                    assertEquals(0, fixture.session.getTimeCount)
                    fixture.session.release.complete(Unit)
                    connect.await()
                    fixture.barrier()
                    assertEquals(ConnectionState.Ready(DEVICE), fixture.manager.state.value)
                    assertTrue(fixture.manager.wantsConnection)
                    assertEquals(DEVICE, fixture.manager.currentDeviceId)
                    assertEquals(generation, fixture.manager.connectGeneration)
                    assertEquals(radio, fixture.manager.radioId)
                    assertSame(container, fixture.manager.services)
                    assertSame(fixture.session, fixture.manager.session)
                    assertSame(fixture.link, fixture.manager.link)
                    assertFalse(container.sendQueue.isRetired)
                    assertEquals(rows, fixture.rows())
                    assertEquals(devices, fixture.devices())
                    fixture.assertNoOutgoing()
                    assertEquals(1, fixture.session.autoFetchStartCount)
                    assertEquals(1, fixture.session.getTimeCount)
                    assertNull(fixture.queries.work)
                    fixture.manager.disconnect()
                    fixture.assertDisconnected()
                }
            }
        } finally {
            fixture.close()
        }
    }
}
