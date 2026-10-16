package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.ConnectionBleFailureExitFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionBleFailureExitSession.Companion.ORIGIN_NAME
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal suspend fun ConnectionBleFailureExitFixture.failedBond(): Deferred<Result<Unit>> {
    seedBondRows()
    callers += scope.launch(start = CoroutineStart.UNDISPATCHED) { manager.state.collect { states += it } }
    val caller = scope.async(CoroutineName(ORIGIN_NAME)) {
        runCatching { manager.connect(DEVICE_A) }
    }.also { callers += it }
    val context = bondLink.disconnected.await()
    assertActiveOwner(caller, context.job)
    assertEquals(ORIGIN_NAME, context[CoroutineName]?.name)
    assertEquals(requireNotNull(bondEntryGeneration) + BOND_RETIREMENT_INCREMENT, manager.connectGeneration)
    barrier()
    assertTrue(requireNotNull(bondStart).isCancelled)
    assertTrue(requireNotNull(bondStart).isCompleted)
    assertNull(manager.services)
    assertNull(manager.session)
    assertNull(manager.link)
    assertTrue(first.closed)
    assertTrue(firstLink.disconnected)
    assertEquals(1, first.closeCount)
    assertEquals(1, firstLink.connectCount)
    assertEquals(1, firstLink.disconnectCount)
    assertTrue(first.getContactsSince.isEmpty())
    assertEquals(0, first.getTimeCount)
    assertEquals(0, first.autoFetchStartCount)
    assertEquals(0, first.autoFetchStopCount)
    assertTrue(database.deviceDao().listAll().isEmpty())
    return caller
}

private const val BOND_RETIREMENT_INCREMENT = 1L
