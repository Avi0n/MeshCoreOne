package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testLoginInfo
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Join/reconnect flow state for [RoomAuthController], driven against the room fakes and a real
 * [RemoteNodeService] login handshake: success returns the session and stores the password, the 15-char cap
 * truncates, the timeout hint surfaces via `onTimeoutKnown`, a login failure and a login timeout map to
 * their structured results, and unchecking remember deletes a previously saved password.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomAuthControllerTest {
    private class Stack(scope: CoroutineScope) {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRoomStore()
        val passwords = InMemoryNodePasswordStore()
        val roomSession = FakeRoomServerSession()
        val remoteNode = RemoteNodeService(nodeSession, store, passwords, scope)
        val service = RoomServerService(
            remoteNode,
            store,
            roomSession,
            passwords,
            scope,
            edges = RoomServerEdges(now = { 1_720_000_000L }),
        )
        val controller = RoomAuthController(service, passwords)
    }

    private fun TestScope.stack(): Stack = Stack(backgroundScope)

    @Test
    fun joinSuccessReturnsSessionStoresPasswordAndReportsTimeout() = runTest {
        val s = stack()
        val contact = roomContact()
        s.controller.password = "pw"

        var result: RoomAuthResult? = null
        val job = launch { result = s.controller.join(contact.radioID, contact) }
        runCurrent()
        s.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        assertIs<RoomAuthResult.Success>(result)
        assertTrue(s.passwords.hasPassword(contact.publicKey))
        assertNotNull(s.controller.timeoutSeconds)
        assertFalse(s.controller.isAuthenticating)
    }

    @Test
    fun joinTruncatesPasswordToFifteenChars() = runTest {
        val s = stack()
        val contact = roomContact()
        s.controller.password = "0123456789ABCDEFGHIJ"
        assertTrue(s.controller.isPasswordTooLong)

        val job = launch { s.controller.join(contact.radioID, contact) }
        runCurrent()
        s.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        assertEquals("0123456789ABCDE", s.nodeSession.sendLoginCalls.single().second)
    }

    @Test
    fun joinFailureMapsToFailedAndStoresNoPassword() = runTest {
        val s = stack()
        val contact = roomContact()
        s.controller.password = "pw"

        var result: RoomAuthResult? = null
        val job = launch { result = s.controller.join(contact.radioID, contact) }
        runCurrent()
        s.nodeSession.emitLoginFailed(contact.publicKey.copyOf(6))
        runCurrent()
        job.join()

        assertIs<RoomAuthResult.Failed>(result)
        assertFalse(s.passwords.hasPassword(contact.publicKey))
        assertFalse(s.controller.isAuthenticating)
    }

    @Test
    fun joinTimeoutMapsToTimeout() = runTest {
        val s = stack()
        val contact = roomContact()
        s.controller.password = "pw"

        var result: RoomAuthResult? = null
        val job = launch { result = s.controller.join(contact.radioID, contact) }
        runCurrent()
        advanceTimeBy(60.seconds)
        runCurrent()
        job.join()

        assertIs<RoomAuthResult.Timeout>(result)
    }

    @Test
    fun uncheckingRememberDeletesSavedPassword() = runTest {
        val s = stack()
        val contact = roomContact()
        s.passwords.storePassword(contact.publicKey, "old")
        s.controller.loadSavedPassword(contact.publicKey)
        assertTrue(s.controller.hasSavedPassword)
        assertEquals("old", s.controller.password)

        s.controller.rememberPassword = false
        val job = launch { s.controller.join(contact.radioID, contact) }
        runCurrent()
        s.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        assertFalse(s.passwords.hasPassword(contact.publicKey))
    }

    @Test
    fun cancelledPasswordDeletionCancelsJoinAndClearsAuthenticatingState() = runTest {
        val s = stack()
        val contact = roomContact()
        s.passwords.storePassword(contact.publicKey, "old")
        val cancelled = CancellationException("delete cancelled")
        val passwords = object : NodePasswordStore by s.passwords {
            override suspend fun deletePassword(publicKey: ByteArray): Unit = throw cancelled
        }
        val controller = RoomAuthController(s.service, passwords)
        controller.loadSavedPassword(contact.publicKey)
        controller.rememberPassword = false

        var thrown: CancellationException? = null
        val job = launch {
            thrown = assertFailsWith<CancellationException> { controller.join(contact.radioID, contact) }
        }
        runCurrent()
        s.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        assertEquals(cancelled, thrown)
        assertFalse(controller.isAuthenticating)
    }

    @Test
    fun reconnectReAuthenticatesExistingRoom() = runTest {
        val s = stack()
        s.store.seedSession(roomSession(isConnected = false))
        s.passwords.storePassword(testPublicKey(), "pw")

        var result: RoomAuthResult? = null
        val job = launch { result = s.controller.reconnect(testUuid(1)) }
        runCurrent()
        s.nodeSession.emitLoginSuccess(testLoginInfo(testPublicKey(), isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        assertIs<RoomAuthResult.Success>(result)
        assertTrue(s.store.fetchSession(testUuid(1))!!.isConnected)
    }
}
