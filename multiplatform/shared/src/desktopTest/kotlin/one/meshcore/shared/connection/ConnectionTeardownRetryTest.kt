package one.meshcore.shared.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.contact.handleDeviceContactDeleted
import one.meshcore.shared.contact.hasPendingDeletedKey
import one.meshcore.shared.fixtures.cancelAndJoinBlocking
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionTeardownRetryTest {
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-teardown-retry", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
    }

    @AfterTest
    fun tearDown() {
        scope.cancelAndJoinBlocking()
        dispatcher.close()
        db.close()
        dbFile.delete()
    }

    @Test
    fun aFailedDisconnectLeavesTheGraphReachableForCleanupRetry() = runBlocking(dispatcher) {
        verifyRetry(keepLink = false)
    }

    @Test
    fun aFailedSessionRetirementLeavesTheGraphReachableForCleanupRetry() = runBlocking(dispatcher) {
        verifyRetry(keepLink = true)
    }

    private suspend fun verifyRetry(keepLink: Boolean) {
        withTimeout(TIMEOUT_MS) {
            val key = ByteArray(PUBLIC_KEY_SIZE) { CONTACT_KEY_BYTE }
            val session = FakeMeshSession(contacts = listOf(sampleContact(key, "Node")))
            val link = FakeMeshLink()
            val failure = IllegalStateException("contact-change callback failed")
            var failNextCallback = false
            val manager = ConnectionManager(
                database = db,
                scope = scope,
                linkFactory = { link },
                sessionFactory = { session },
                nowEpochSeconds = { NOW_SECONDS },
                ports = ConnectionManagerPorts(
                    onContactsChanged = {
                        if (failNextCallback) {
                            failNextCallback = false
                            throw failure
                        }
                    },
                ),
            )
            manager.connect(DEVICE)
            assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
            val services = requireNotNull(manager.services)
            assertTrue(session.eventSubscribers.value > 0)
            assertTrue(services.messageService.isAckExpiryCheckingActive)
            assertTrue(db.contactDao().listByRadio(services.radioId).any { it.publicKey.contentEquals(key) })
            manager.setAppActive(false)
            assertFalse(services.contactService.handleDeviceContactDeleted(services.radioId, key))
            assertTrue(services.contactService.hasPendingDeletedKey(key))
            failNextCallback = true

            val thrown = assertFailsWith<IllegalStateException> { retire(manager, keepLink) }
            assertSame(failure, thrown)
            assertFalse(failNextCallback)
            assertFalse(db.contactDao().listByRadio(services.radioId).any { it.publicKey.contentEquals(key) })
            assertSame(services, manager.services)
            assertSame(session, manager.session)
            assertSame(link, manager.link)
            assertEquals(0, session.closeCount)
            assertEquals(0, link.disconnectCount)
            assertTrue(session.eventSubscribers.value > 0)
            assertTrue(services.messageService.isAckExpiryCheckingActive)
            assertFalse(services.remoteNodeService.connectionRetired.isCompleted)

            retire(manager, keepLink)
            session.eventSubscribers.first { it == 0 }
            assertFalse(services.messageService.isAckExpiryCheckingActive)
            assertTrue(services.remoteNodeService.connectionRetired.isCompleted)
            assertTrue(requireNotNull(scope.coroutineContext[Job]).isActive)
            assertNull(manager.services)
            assertNull(manager.session)
            assertEquals(1, session.closeCount)
            if (keepLink) {
                assertSame(link, manager.link)
                assertEquals(0, link.disconnectCount)
                manager.disconnect()
            } else {
                assertNull(manager.link)
            }
            assertEquals(1, link.disconnectCount)
        }
    }

    private suspend fun retire(manager: ConnectionManager, keepLink: Boolean) {
        if (keepLink) manager.dropSessionKeepingLink() else manager.disconnect()
    }

    private companion object {
        const val DEVICE = "AA:BB:CC:DD:EE:74"
        const val PUBLIC_KEY_SIZE = 32
        const val CONTACT_KEY_BYTE: Byte = 9
        const val NOW_SECONDS = 1_700_000_000.0
        const val TIMEOUT_MS = 10_000L
    }
}
