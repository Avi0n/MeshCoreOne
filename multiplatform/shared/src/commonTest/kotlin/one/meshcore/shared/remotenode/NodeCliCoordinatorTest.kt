package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * CLI request/response correlation for [NodeCliCoordinator]: the single in-flight slot per node, the
 * wire-prefix echo that attributes replies deterministically, the shape-validation fallback for firmware
 * that doesn't echo, admin gating, timeout, and the post-password-change credential clear.
 * `runCurrent()` lets the event monitor subscribe and route a pushed response without advancing time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliCoordinatorTest {
    private companion object {
        const val COMMAND_COUNT = 50
    }

    private fun adminSession(id: Int = 1, seed: Int = 0x42) = testSession(
        id = testUuid(id),
        publicKey = testPublicKey(seed),
        isConnected = true,
        permissionLevel = RoomPermissionLevel.ADMIN,
    )

    @Test
    fun concurrentRepliesAttributeToTheCorrectNodeByPrefix() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val nodeA = adminSession(id = 1, seed = 0x11)
        val nodeB = adminSession(id = 2, seed = 0x22)
        store.saveSession(nodeA)
        store.saveSession(nodeB)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var resultA: String? = null
        var resultB: String? = null
        launch { resultA = cli.sendCLICommand(nodeA.id, "get name") }
        launch { resultB = cli.sendCLICommand(nodeB.id, "get name") }
        runCurrent()
        // Different nodes hold independent slots, so both commands go out concurrently.
        assertEquals(2, session.sendCommandCalls.size)

        // Reply out of order (B before A); each attributes by its 6-byte sender prefix.
        session.emitCliResponse("NodeB", testPublicKey(0x22).copyOf(6))
        runCurrent()
        session.emitCliResponse("NodeA", testPublicKey(0x11).copyOf(6))
        runCurrent()

        assertEquals("NodeA", resultA)
        assertEquals("NodeB", resultB)
    }

    @Test
    fun secondCommandToSameNodeQueuesBehindTheFirst() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var firstResult: String? = null
        var secondResult: String? = null
        launch { firstResult = cli.sendRawCLICommand(dto.id, "cmd1") }
        val secondJob = launch { secondResult = cli.sendRawCLICommand(dto.id, "cmd2") }
        runCurrent()
        // Only the first command is in flight; the second waits on the node's CLI slot.
        assertEquals(1, session.sendCommandCalls.size)
        assertFalse(secondJob.isCompleted)

        session.emitCliResponse("reply1")
        runCurrent()
        assertEquals("reply1", firstResult)
        // Releasing the slot lets the queued second command go out.
        assertEquals(2, session.sendCommandCalls.size)

        session.emitCliResponse("reply2")
        runCurrent()
        assertEquals("reply2", secondResult)
    }

    @Test
    fun cliWaiterPumpsGetMessageToDequeueTheReply() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        session.getMessageResults += GetMessageResult.ContactMessageReceived(
            testContactMessage("00|pong", dto.publicKey.copyOf(6)),
        )
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var result: String? = null
        launch { result = cli.sendCLICommand(dto.id, "get name") }
        runCurrent()

        assertTrue(session.getMessageCalls.isNotEmpty(), "CLI wait must send GET_MSG so firmware dequeues the reply")
        assertEquals("pong", result)
    }

    @Test
    fun cancellingAQueuedWaiterStillFreesTheSlotForLaterCommands() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        // The first command holds the node's single slot.
        var firstResult: String? = null
        launch { firstResult = cli.sendRawCLICommand(dto.id, "cmd1") }
        runCurrent()
        assertEquals(1, session.sendCommandCalls.size)

        // A second command queues behind it, then is cancelled while still waiting on the slot.
        val secondJob = launch { cli.sendRawCLICommand(dto.id, "cmd2") }
        runCurrent()
        assertEquals(1, session.sendCommandCalls.size)
        secondJob.cancel()
        runCurrent()

        // Completing the first command releases the slot; the cancelled waiter must not have wedged it.
        session.emitCliResponse("reply1")
        runCurrent()
        assertEquals("reply1", firstResult)

        // A third command still acquires the freed slot and goes out (cmd2 never sent, so this is the 2nd send).
        var thirdResult: String? = null
        launch { thirdResult = cli.sendRawCLICommand(dto.id, "cmd3") }
        runCurrent()
        assertEquals(2, session.sendCommandCalls.size)
        session.emitCliResponse("reply3")
        runCurrent()
        assertEquals("reply3", thirdResult)
    }

    @Test
    fun concurrentCommandsMintUniqueWirePrefixes() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        // One admin session per node so every command holds an independent slot and all go out concurrently.
        val nodes = (1..COMMAND_COUNT).map { adminSession(id = it, seed = it).also { s -> store.saveSession(s) } }
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        val jobs = nodes.map { node -> launch { cli.sendCLICommand(node.id, "get name") } }
        runCurrent()

        assertEquals(COMMAND_COUNT, session.sendCommandCalls.size)
        val prefixes = session.sendCommandCalls.map { it.second.substringBefore('|') }
        assertEquals(prefixes.size, prefixes.toSet().size)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun echoedWirePrefixAttributesDeterministicallyAndDropsStale() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var result: String? = null
        val job = launch { result = cli.sendCLICommand(dto.id, "get name") }
        runCurrent()
        // The first command of a fresh coordinator carries wire prefix "00|".
        assertTrue(session.sendCommandCalls.single().second.startsWith("00|"))

        // A reply echoing a foreign prefix belongs to an earlier command and is dropped.
        session.emitCliResponse("FF|stale")
        runCurrent()
        assertFalse(job.isCompleted)

        // The reply echoing the pending prefix resolves it, with the prefix stripped.
        session.emitCliResponse("00|MyNode")
        runCurrent()
        assertEquals("MyNode", result)
    }

    @Test
    fun rawCommandReceivesAnyResponseVerbatim() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var result: String? = null
        val job = launch { result = cli.sendRawCLICommand(dto.id, "some raw command") }
        runCurrent()
        session.emitCliResponse("arbitrary device text")
        runCurrent()
        job.join()

        assertEquals("arbitrary device text", result)
    }

    @Test
    fun structuredReplyToWrongShapeIsDroppedForUnprefixedFirmware() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var result: String? = null
        val job = launch { result = cli.sendCLICommand(dto.id, "get tx") }
        runCurrent()

        // A non-numeric, unprefixed reply is implausible for a structured "get tx" and is dropped.
        session.emitCliResponse("not a power")
        runCurrent()
        assertFalse(job.isCompleted)

        session.emitCliResponse("22")
        runCurrent()
        assertEquals("22", result)
    }

    @Test
    fun cliDropDumpLinesMatchIos() {
        assertEquals("Unmatched CLI response (no pending request): hello", unmatchedCliResponseLine("hello"))
        assertEquals(
            "Dropping stale CLI response with prefix FF| while awaiting 00|",
            droppingStaleCliResponseLine("FF|", "00|"),
        )
        assertEquals(
            "Dropping CLI response that doesn't match pending 'get tx': not a power",
            droppingCliResponseMismatchLine("get tx", "not a power"),
        )
        assertEquals("Dropping CLI response: no contact found for sender", DROPPING_CLI_RESPONSE_NO_CONTACT)
        assertEquals(50, CLI_RESPONSE_LOG_PREFIX)
    }

    @Test
    fun stalePrefixedReplyRecordsIosDropDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        val job = launch { cli.sendCLICommand(dto.id, "get name") }
        runCurrent()
        session.emitCliResponse("FF|stale")
        runCurrent()
        job.cancel()

        val line = recorded.single { it.message.contains("Dropping stale CLI response") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
        assertEquals(droppingStaleCliResponseLine("FF|", "00|"), line.message)
    }

    @Test
    fun unprefixedShapeMismatchRecordsIosDropDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        val job = launch { cli.sendCLICommand(dto.id, "get tx") }
        runCurrent()
        session.emitCliResponse("not a power")
        runCurrent()
        job.cancel()

        val line = recorded.single { it.message.contains("doesn't match pending") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals(droppingCliResponseMismatchLine("get tx", "not a power"), line.message)
    }

    @Test
    fun observerWithNoContactRecordsIosDropDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val cli = NodeCliCoordinator(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        cli.addCLIResponseObserver { _, _, _ -> }
        runCurrent()

        session.emitCliResponse("OK")
        runCurrent()

        val line = recorded.single { it.message == DROPPING_CLI_RESPONSE_NO_CONTACT }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
    }

    @Test
    fun nonAdminSessionIsRejected() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(permissionLevel = RoomPermissionLevel.READ_WRITE)
        store.saveSession(dto)
        val cli = NodeCliCoordinator(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)

        val thrown = runCatching { cli.sendCLICommand(dto.id, "get name") }.exceptionOrNull()
        assertTrue(thrown is RemoteNodeError.PermissionDenied)
    }

    @Test
    fun unknownSessionThrowsSessionNotFound() = runTest {
        val cli = NodeCliCoordinator(
            FakeNodeSession(),
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
        )
        val thrown = runCatching { cli.sendCLICommand(testUuid(404), "get name") }.exceptionOrNull()
        assertTrue(thrown is RemoteNodeError.SessionNotFound)
    }

    @Test
    fun noResponseTimesOut() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { cli.sendCLICommand(dto.id, "get name") }.exceptionOrNull() }
        advanceUntilIdle()
        job.join()

        assertTrue(thrown is RemoteNodeError.Timeout)
    }

    @Test
    fun sendFailureMapsToSessionError() = runTest {
        val session = FakeNodeSession().apply { sendCommandError = MeshError.NotConnected }
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)

        val error = runCatching { cli.sendCLICommand(dto.id, "get name") }.exceptionOrNull()
        assertTrue(error is RemoteNodeError.SessionError)
        assertEquals(MeshError.NotConnected, (error as RemoteNodeError.SessionError).error)
    }

    @Test
    fun adminPasswordChangeClearsStoredCredential() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = adminSession()
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "old-pw")
        val cli = NodeCliCoordinator(session, store, pw, backgroundScope)
        runCurrent()

        val job = launch { cli.sendRawCLICommand(dto.id, "password newsecret") }
        runCurrent()
        session.emitCliResponse("OK")
        runCurrent()
        job.join()

        assertFalse(pw.hasPassword(dto.publicKey))
    }

    @Test
    fun guestPasswordChangeKeepsStoredCredential() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = adminSession()
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "old-pw")
        val cli = NodeCliCoordinator(session, store, pw, backgroundScope)
        runCurrent()

        val job = launch { cli.sendRawCLICommand(dto.id, "set guest.password newsecret") }
        runCurrent()
        session.emitCliResponse("OK")
        runCurrent()
        job.join()

        // Only an admin `password ` change clears the credential, not a guest.password write.
        assertTrue(pw.hasPassword(dto.publicKey))
    }

    @Test
    fun passwordChangeDumpLinesMatchIos() {
        val sessionID = testUuid(1)
        assertEquals(
            "Cleared stored password after password change for session ${sessionID.value}",
            clearedStoredPasswordAfterPasswordChangeLine(sessionID),
        )
        assertEquals(
            "Failed to clear stored password for session ${sessionID.value}: db locked",
            failedToClearStoredPasswordLine(sessionID, "db locked"),
        )
    }

    @Test
    fun adminPasswordChangeRecordsIosClearDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = adminSession()
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "old-pw")
        val cli = NodeCliCoordinator(
            session,
            store,
            pw,
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        val job = launch { cli.sendRawCLICommand(dto.id, "password newsecret") }
        runCurrent()
        session.emitCliResponse("OK")
        runCurrent()
        job.join()
        runCurrent()

        assertFalse(pw.hasPassword(dto.publicKey))
        val line = recorded.single { it.message.contains("Cleared stored password after password change") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("RemoteNode", line.category)
        assertEquals(clearedStoredPasswordAfterPasswordChangeLine(dto.id), line.message)
    }

    @Test
    fun passwordClearPersistFaultRecordsIosDumpAndDoesNotThrow() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val pw = object : NodePasswordStore {
            override suspend fun storePassword(publicKey: ByteArray, password: String) = Unit
            override suspend fun retrievePassword(publicKey: ByteArray): String? = null
            override suspend fun deletePassword(publicKey: ByteArray) = error("db locked")
            override suspend fun hasPassword(publicKey: ByteArray): Boolean = false
        }
        val cli = NodeCliCoordinator(
            session,
            store,
            pw,
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        var result: String? = null
        val job = launch { result = cli.sendRawCLICommand(dto.id, "password newsecret") }
        runCurrent()
        session.emitCliResponse("OK")
        runCurrent()
        job.join()
        runCurrent()

        assertEquals("OK", result)
        val line = recorded.single { it.message.contains("Failed to clear stored password") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
        assertEquals(failedToClearStoredPasswordLine(dto.id, "db locked"), line.message)
    }

    @Test
    fun directPathCliTimeoutResetsToFloodAndRetries() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        store.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var result: String? = null
        val job = launch { result = cli.sendCLICommand(dto.id, "get name") }
        runCurrent()
        assertEquals(1, session.sendCommandCalls.size)

        // No reply: the first exchange times out on its direct path.
        advanceTimeBy(10_001)
        runCurrent()
        // Path reset to flood on the radio, local row mirrored, and the command retried once.
        assertEquals(1, session.resetPathCalls.size)
        assertEquals(1, store.savedContactFrames.size)
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, store.savedContactFrames.single().outPathLength)
        assertEquals(2, session.sendCommandCalls.size)

        // The retry exchange carries the next wire prefix ("01|"); its echoed reply resolves the command.
        session.emitCliResponse("01|MyNode")
        runCurrent()
        job.join()
        assertEquals("MyNode", result)
    }

    @Test
    fun directPathCliTimeoutRecordsRemoteCliFloodResetDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        store.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val cli = NodeCliCoordinator(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        val job = launch { cli.sendCLICommand(dto.id, "get name") }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        session.emitCliResponse("01|MyNode")
        runCurrent()
        job.join()
        runCurrent()

        val line = recorded.single { it.message.contains("remoteCLI: direct-path timeout") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("RemoteNode", line.category)
        assertTrue(line.message.contains("resetting path to flood"))
    }

    @Test
    fun pathResetPersistFaultRecordsIosDumpAndStillRetries() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val backing = InMemoryRemoteNodeStore()
        val dto = adminSession()
        backing.saveSession(dto)
        backing.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        backing.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val store = object : RemoteNodeStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame) {
                error("db locked")
            }
        }
        val cli = NodeCliCoordinator(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        var result: String? = null
        val job = launch { result = cli.sendCLICommand(dto.id, "get name") }
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        session.emitCliResponse("01|MyNode")
        runCurrent()
        job.join()
        runCurrent()

        assertEquals("MyNode", result)
        assertEquals(1, session.resetPathCalls.size)
        assertEquals(2, session.sendCommandCalls.size)
        val line = recorded.single { it.message.contains("Path reset on radio but failed to sync local contact") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun pathResetFailureRecordsDumpAndDoesNotRetry() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession().apply { resetPathError = IllegalStateException("radio busy") }
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        store.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val cli = NodeCliCoordinator(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { cli.sendCLICommand(dto.id, "get name") }.exceptionOrNull() }
        advanceUntilIdle()
        job.join()
        runCurrent()

        assertTrue(thrown is RemoteNodeError.Timeout)
        assertEquals(1, session.sendCommandCalls.size)
        val line = recorded.single { it.message.contains("remoteCLI: path reset failed") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
        assertTrue(line.message.contains("radio busy"))
    }

    @Test
    fun floodRetryTimeoutRecordsIosDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        store.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val cli = NodeCliCoordinator(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RemoteNode", record = { recorded += it }),
        )
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { cli.sendCLICommand(dto.id, "get name") }.exceptionOrNull() }
        advanceUntilIdle()
        job.join()
        runCurrent()

        assertTrue(thrown is RemoteNodeError.Timeout)
        assertEquals(1, session.resetPathCalls.size)
        assertEquals(2, session.sendCommandCalls.size)
        val line = recorded.single { it.message.contains("remoteCLI: flood retry also timed out") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNode", line.category)
    }

    @Test
    fun floodRoutedCliTimeoutDoesNotAttemptPathRecovery() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = true)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { cli.sendCLICommand(dto.id, "get name") }.exceptionOrNull() }
        advanceUntilIdle()
        job.join()

        assertTrue(thrown is RemoteNodeError.Timeout)
        // An already-flood contact has no stale direct path to salvage: one send, no reset.
        assertEquals(1, session.sendCommandCalls.size)
        assertTrue(session.resetPathCalls.isEmpty())
    }

    @Test
    fun rebootIsFireAndForgetAndSkipsPathRecovery() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        store.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job =
            launch {
                thrown = runCatching { cli.sendRawCLICommand(dto.id, "reboot", 2.seconds) }.exceptionOrNull()
            }
        advanceUntilIdle()
        job.join()

        // Reboot times out by design; it must send exactly once and never fire a second reboot via recovery.
        assertTrue(thrown is RemoteNodeError.Timeout)
        assertEquals(1, session.sendCommandCalls.size)
        assertTrue(session.resetPathCalls.isEmpty())
    }

    @Test
    fun commandIsSentToTheSessionPublicKey() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = adminSession()
        store.saveSession(dto)
        val cli = NodeCliCoordinator(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        val job = launch { cli.sendCLICommand(dto.id, "get name") }
        runCurrent()
        assertContentEquals(dto.publicKey, session.sendCommandCalls.single().first)
        job.cancel()
    }
}
