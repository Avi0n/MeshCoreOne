package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeKeepAliveIntervalTest {
    @Test
    fun keepAliveCadenceAndStoppingAreTheSameBeforeAndAfterLogin() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        try {
            repeat(2) { phase ->
                if (phase == 1) {
                    val login = launch { assertTrue(service.login(dto.id, password = "pw").success) }
                    runCurrent()
                    session.emitLoginSuccess(testLoginInfo(dto.publicKey))
                    runCurrent()
                    login.join()
                }

                val previousSends = session.sendKeepAliveCalls.size
                service.startSessionKeepAlive(dto.id, dto.publicKey)
                runCurrent()
                assertEquals(previousSends + 1, session.sendKeepAliveCalls.size)

                advanceTimeBy(89_999)
                runCurrent()
                assertEquals(previousSends + 1, session.sendKeepAliveCalls.size)
                advanceTimeBy(1)
                runCurrent()
                assertEquals(previousSends + 2, session.sendKeepAliveCalls.size)

                advanceTimeBy(90_000)
                runCurrent()
                assertEquals(previousSends + 3, session.sendKeepAliveCalls.size)

                service.stopSessionKeepAlive(dto.id)
                advanceTimeBy(180_000)
                runCurrent()
                assertEquals(previousSends + 3, session.sendKeepAliveCalls.size)
            }
        } finally {
            service.stopAllKeepAlives()
            service.stopEventMonitoring()
        }
    }
}
