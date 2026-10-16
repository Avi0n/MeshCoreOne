package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactProtocolErrorCode
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val FLOOD_PATH_SENTINEL = 0xFF
private const val FLOOD_LOGIN_TIMEOUT_SECONDS = 20

/**
 * Login/logout/keep-alive/status flows for [RemoteNodeService], driven against fakes: the exact session
 * ops issued, the push-driven login state machine, the keep-alive retry loop, and status request-response.
 * `runCurrent()` dispatches pending work without advancing virtual time, so a parked login's timeout does
 * not fire before the test drives its push.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeServiceTest {
    // MARK: Login

    @Test
    fun loginSuccessMarksConnectedAndEmitsEvent() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(permissionLevel = RoomPermissionLevel.GUEST)
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()

        var result: LoginResult? = null
        val job = launch { result = service.login(dto.id, password = "pw") }
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)
        assertEquals("pw", session.sendLoginCalls[0].second)

        session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = true))
        runCurrent()
        job.join()

        assertEquals(true, result?.success)
        assertEquals(true, result?.isAdmin)
        val updated = store.fetchSessionById(dto.id)!!
        assertTrue(updated.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, updated.permissionLevel)
        assertTrue(events.any { it is RemoteNodeEvent.SessionStateChanged && it.isConnected })
    }

    @Test
    fun loginPersistFaultRecordsDumpCompletesLoginAndKeepsMonitor() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val inner = InMemoryRemoteNodeStore()
        val dto = testSession(permissionLevel = RoomPermissionLevel.GUEST)
        inner.saveSession(dto)
        val store = object : RemoteNodeStore by inner {
            override suspend fun updateSessionConnection(
                id: Uuid,
                isConnected: Boolean,
                permissionLevel: RoomPermissionLevel,
            ) {
                error("db locked")
            }
        }
        val session = FakeNodeSession()
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                scope = backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )

        var first: LoginResult? = null
        val firstJob = launch { first = service.login(dto.id, password = "pw") }
        runCurrent()
        session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = true))
        runCurrent()
        firstJob.join()

        assertEquals(true, first?.success)
        val line = recorded.single { it.message.contains("handleLoginResult: failed to update session state") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("RemoteNodeService", line.category)
        assertTrue(line.message.contains("db locked"))

        var second: LoginResult? = null
        val secondJob = launch { second = service.login(dto.id, password = "pw") }
        runCurrent()
        session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = true))
        runCurrent()
        secondJob.join()

        assertEquals(true, second?.success)
    }

    @Test
    fun loginResultInvalidPrefixRecordsIosDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val service = RemoteNodeService(
            session,
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        session.emitLoginSuccess(testLoginInfo().copy(publicKeyPrefix = byteArrayOf(0x01)))
        runCurrent()

        val line = recorded.single { it.message.contains("Login result has invalid prefix length") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("1"))
    }

    @Test
    fun loginResultWithNoPendingRequestRecordsIosDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val service = RemoteNodeService(
            session,
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        session.emitLoginSuccess(testLoginInfo(testPublicKey(0x33)))
        runCurrent()

        val line = recorded.single { it.message.contains("Login result with no pending request") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNodeService", line.category)
    }

    @Test
    fun loginResultMissingSessionRecordsIosDumpAndCompletes() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val session = FakeNodeSession()
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        var result: LoginResult? = null
        val job = launch { result = service.login(dto.id, password = "pw") }
        runCurrent()
        store.deleteSession(dto.id)
        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job.join()
        runCurrent()

        assertEquals(true, result?.success)
        val line = recorded.single { it.message.contains("handleLoginResult: no session found for prefix") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("database may be corrupted"))
    }

    @Test
    fun secondLoginOverwritesPendingAndRecordsIosDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        var first: Throwable? = null
        var second: LoginResult? = null
        val job1 = launch { first = runCatching { service.login(dto.id, password = "pw") }.exceptionOrNull() }
        runCurrent()
        val job2 = launch { second = service.login(dto.id, password = "pw") }
        runCurrent()
        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job1.join()
        job2.join()
        runCurrent()

        assertTrue(first is RemoteNodeError.Cancelled)
        assertEquals(true, second?.success)
        val line = recorded.single { it.message.contains("Overwriting pending login for prefix") }
        assertEquals(DebugLogLevel.WARNING, line.level)
    }

    @Test
    fun loginRetransmitFaultRecordsIosDumpAndKeepsWaiting() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession().apply {
            sentInfo = testSentInfo(suggestedTimeoutMs = 3000)
            sendLoginErrorForCall = { call -> if (call == 2) MeshError.NotConnected else null }
        }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        val job = launch { service.login(dto.id, password = "pw") }
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()
        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job.join()
        runCurrent()

        val line = recorded.single { it.message.contains("login: retransmit #1 failed") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("not connected"))
    }

    @Test
    fun loginDumpLinesMatchIos() {
        val sessionID = testUuid(1)
        assertEquals("loginSuccess received for prefix 424242424242", loginSuccessReceivedLine("424242424242"))
        assertEquals(
            "Logout send failed (ignoring): not connected",
            logoutSendFailedLine("not connected"),
        )
        assertEquals("Re-added missing contact to radio during login", RE_ADDED_MISSING_CONTACT_DURING_LOGIN)
        assertEquals(
            "createSession: reusing existing session ${sessionID.value} for 424242424242, isConnected=true",
            createSessionReusingLine(sessionID, "424242424242", isConnected = true),
        )
        assertEquals("createSession: creating new session for 424242424242", createSessionCreatingLine("424242424242"))
        assertEquals(
            "createSession: saved session ${sessionID.value} for 424242424242",
            createSessionSavedLine(sessionID, "424242424242"),
        )
        assertEquals(
            "handleLoginResult: looking for prefix 424242424242, pending keys: [424242424242]",
            handleLoginResultLookingLine("424242424242", listOf("424242424242")),
        )
        assertEquals(
            "handleLoginResult: found continuation for prefix 424242424242",
            handleLoginResultFoundLine("424242424242"),
        )
        assertEquals(
            "handleLoginResult: updating session ${sessionID.value} isConnected=true, permission=2",
            handleLoginResultUpdatingLine(sessionID, 2),
        )
        assertEquals(
            "handleLoginResult: verified session ${sessionID.value} isConnected=true",
            handleLoginResultVerifiedLine(sessionID),
        )
        assertEquals(
            "login: registering pending login for prefix 424242424242",
            loginRegisteringPendingLine("424242424242"),
        )
        assertEquals(
            "login: continuation consumed for prefix 424242424242; stopping retransmits",
            loginContinuationConsumedLine("424242424242"),
        )
        assertEquals("login: retransmit #1 for prefix 424242424242", loginRetransmitLine(1, "424242424242"))
        assertEquals(
            "login: timeout elapsed but continuation already consumed for prefix 424242424242",
            loginTimeoutAlreadyConsumedLine("424242424242"),
        )
    }

    @Test
    fun loginSuccessRecordsIosDumpLines() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(permissionLevel = RoomPermissionLevel.GUEST)
        store.saveSession(dto)
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                scope = backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        val job = launch { service.login(dto.id, password = "pw") }
        runCurrent()
        session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = true, serverTime = 1_700_001_000L))
        runCurrent()
        job.join()

        assertTrue(recorded.any { it.message == loginRegisteringPendingLine("424242424242") })
        assertTrue(recorded.any { it.message.startsWith("login: send succeeded, starting") })
        val success = recorded.single { it.message.startsWith("loginSuccess received for prefix") }
        assertEquals(DebugLogLevel.INFO, success.level)
        assertEquals("RemoteNodeService", success.category)
        assertEquals(loginSuccessReceivedLine("424242424242"), success.message)
        val looking = recorded.single { it.message.startsWith("handleLoginResult: looking") }
        assertEquals(handleLoginResultLookingLine("424242424242", listOf("424242424242")), looking.message)
        assertEquals(
            handleLoginResultFoundLine("424242424242"),
            recorded.single {
                it.message.startsWith("handleLoginResult: found")
            }.message,
        )
        assertEquals(
            handleLoginResultUpdatingLine(dto.id, RoomPermissionLevel.ADMIN.raw),
            recorded.single { it.message.startsWith("handleLoginResult: updating") }.message,
        )
        assertEquals(
            handleLoginResultVerifiedLine(dto.id),
            recorded.single {
                it.message.startsWith("handleLoginResult: verified")
            }.message,
        )
        assertTrue(recorded.none { it.message.startsWith("Login clock drift for session") })
    }

    @Test
    fun loginFailureThrowsLoginFailed() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { service.login(dto.id, password = "pw") }.exceptionOrNull() }
        runCurrent()
        session.emitLoginFailed(dto.publicKey.copyOf(6))
        runCurrent()
        job.join()

        assertTrue(thrown is RemoteNodeError.LoginFailed)
        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)
    }

    @Test
    fun loginTimeoutReportsSecondsAndThrows() = runTest {
        val session = FakeNodeSession().apply { sentInfo = testSentInfo(suggestedTimeoutMs = 1000) }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        var reported: Int? = null
        var thrown: Throwable? = null
        val job = launch {
            thrown = runCatching {
                service.login(dto.id, password = "pw", pathLength = 0, onTimeoutKnown = { reported = it })
            }.exceptionOrNull()
        }
        runCurrent()
        // round-trip 2s vs direct floor 5s -> 5s login timeout, reported as a whole-second countdown.
        assertEquals(5, reported)

        advanceUntilIdle()
        job.join()
        runCurrent()
        assertTrue(thrown is RemoteNodeError.Timeout)
        val line = recorded.single { it.message.contains("Login timeout after") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("session ${dto.id}"))
        assertTrue(line.message.contains("prefix "))
    }

    @Test
    fun floodLoginBudgetsTheLoginCeiling() = runTest {
        val session = FakeNodeSession().apply { sentInfo = testSentInfo(suggestedTimeoutMs = 1000) }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var reported: Int? = null
        val job = launch {
            runCatching {
                service.login(
                    dto.id,
                    password = "pw",
                    pathLength = FLOOD_PATH_SENTINEL,
                    onTimeoutKnown = { reported = it },
                )
            }
        }
        runCurrent()
        // The flood sentinel has no decodable hop count: budget the maximum, clamped to the login ceiling.
        assertEquals(FLOOD_LOGIN_TIMEOUT_SECONDS, reported)

        advanceUntilIdle()
        job.join()
    }

    @Test
    fun secondLoginForSamePrefixCancelsTheFirst() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var first: Throwable? = null
        var second: LoginResult? = null
        val job1 = launch { first = runCatching { service.login(dto.id, password = "pw") }.exceptionOrNull() }
        runCurrent()
        val job2 = launch { second = service.login(dto.id, password = "pw") }
        runCurrent()

        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job1.join()
        job2.join()

        assertTrue(first is RemoteNodeError.Cancelled)
        assertEquals(true, second?.success)
    }

    @Test
    fun loginFallsBackToStoredPassword() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession()
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "stored-pw")
        val service = RemoteNodeService(session, store, pw, backgroundScope)
        runCurrent()

        val job = launch { service.login(dto.id) }
        runCurrent()
        assertEquals("stored-pw", session.sendLoginCalls.single().second)

        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job.join()
    }
}

/**
 * Login heal, logout, and the keep-alive loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeServiceSessionTest {
    @Test
    fun loginWithoutPasswordThrowsPasswordNotFound() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)

        assertFailsWith<RemoteNodeError.PasswordNotFound> { service.login(dto.id) }
    }

    @Test
    fun loginUnknownSessionThrowsSessionNotFound() = runTest {
        val service = RemoteNodeService(
            FakeNodeSession(),
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
        )
        assertFailsWith<RemoteNodeError.SessionNotFound> { service.login(testUuid(404), password = "pw") }
    }

    @Test
    fun sendErrorResumesLoginWithSessionError() = runTest {
        val session = FakeNodeSession().apply { sendLoginError = MeshError.NotConnected }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { service.login(dto.id, password = "pw") }.exceptionOrNull() }
        runCurrent()
        job.join()

        val error = thrown as RemoteNodeError.SessionError
        assertEquals(MeshError.NotConnected, error.error)
        assertEquals(0, service.pendingLoginCount())
    }

    @Test
    fun loginRetransmitsAtFirmwareSuggestedSpacingWhileWaiting() = runTest {
        // 3s suggested round-trip: retransmit spacing is 3s and the login deadline is 6s.
        val session = FakeNodeSession().apply { sentInfo = testSentInfo(suggestedTimeoutMs = 3000) }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        val job = launch { service.login(dto.id, password = "pw") }
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)

        advanceTimeBy(2_999)
        runCurrent()
        // No retransmit before one firmware-suggested interval has elapsed.
        assertEquals(1, session.sendLoginCalls.size)

        advanceTimeBy(2)
        runCurrent()
        // One retransmit at the interval boundary, carrying the same password.
        assertEquals(2, session.sendLoginCalls.size)
        assertEquals("pw", session.sendLoginCalls[1].second)

        // The success push consumes the continuation and stops further retransmits.
        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job.join()
        advanceUntilIdle()
        assertEquals(2, session.sendLoginCalls.size)
    }

    @Test
    fun loginHealsMissingContactThenRetriesAndSucceeds() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession().apply {
            sendLoginErrorForCall = { call ->
                if (call == 1) MeshError.DeviceError(ContactProtocolErrorCode.NOT_FOUND) else null
            }
        }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        store.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        var result: LoginResult? = null
        val job = launch { result = service.login(dto.id, password = "pw") }
        runCurrent()
        // notFound on the first send: the local contact was re-added flood-routed and login retried.
        assertEquals(1, session.addContactCalls.size)
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, session.addContactCalls.single().outPathLength)
        assertEquals(2, session.sendLoginCalls.size)
        assertEquals(1, store.savedContactFrames.size)

        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job.join()
        runCurrent()
        assertEquals(true, result?.success)
        val line = recorded.single { it.message == RE_ADDED_MISSING_CONTACT_DURING_LOGIN }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("RemoteNodeService", line.category)
    }

    @Test
    fun loginHealPersistFaultRecordsIosDumpAndStillRetries() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession().apply {
            sendLoginErrorForCall = { call ->
                if (call == 1) MeshError.DeviceError(ContactProtocolErrorCode.NOT_FOUND) else null
            }
        }
        val backing = InMemoryRemoteNodeStore()
        val dto = testSession()
        backing.saveSession(dto)
        backing.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val store = object : RemoteNodeStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame) {
                error("db locked")
            }
        }
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        var result: LoginResult? = null
        val job = launch { result = service.login(dto.id, password = "pw") }
        runCurrent()
        session.emitLoginSuccess(testLoginInfo(dto.publicKey))
        runCurrent()
        job.join()
        runCurrent()

        assertEquals(true, result?.success)
        assertEquals(1, session.addContactCalls.size)
        assertEquals(2, session.sendLoginCalls.size)
        val line = recorded.single { it.message.contains("Re-added contact to radio but failed to sync local row") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RemoteNodeService", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun loginHealThrowsRadioContactsFullWhenTableFull() = runTest {
        val session = FakeNodeSession().apply {
            sendLoginErrorForCall = { call ->
                if (call == 1) MeshError.DeviceError(ContactProtocolErrorCode.NOT_FOUND) else null
            }
            addContactError = MeshError.DeviceError(ContactProtocolErrorCode.TABLE_FULL)
        }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        store.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { service.login(dto.id, password = "pw") }.exceptionOrNull() }
        runCurrent()
        job.join()

        assertTrue(thrown is RemoteNodeError.RadioContactsFull)
        // A full table is terminal: no login retry after the failed add.
        assertEquals(1, session.sendLoginCalls.size)
    }

    @Test
    fun loginHealThrowsContactNotFoundWhenLocalContactAbsent() = runTest {
        val session = FakeNodeSession().apply {
            sendLoginErrorForCall = { call ->
                if (call == 1) MeshError.DeviceError(ContactProtocolErrorCode.NOT_FOUND) else null
            }
        }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { service.login(dto.id, password = "pw") }.exceptionOrNull() }
        runCurrent()
        job.join()

        assertTrue(thrown is RemoteNodeError.ContactNotFound)
        assertTrue(session.addContactCalls.isEmpty())
    }

    @Test
    fun failedRoomLoginAuditsRoomTargetNotRepeater() = runTest {
        val recorded = mutableListOf<String>()
        val audit = CommandAuditLogger(record = { recorded += it.message })
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(role = RemoteNodeRole.ROOM_SERVER)
        store.saveSession(dto)
        val service =
            RemoteNodeService(
                session,
                store,
                InMemoryNodePasswordStore(),
                backgroundScope,
                options = RemoteNodeServiceOptions(auditLogger = audit),
            )
        runCurrent()

        val job = launch { runCatching { service.login(dto.id, password = "pw") } }
        runCurrent()
        session.emitLoginFailed(dto.publicKey.copyOf(6))
        runCurrent()
        job.join()

        val failLine = recorded.single { it.contains("LOGIN_FAIL") }
        assertTrue(failLine.contains(CommandAuditLogger.Target.ROOM.token), failLine)
    }

    // MARK: Teardown

    @Test
    fun stopAllKeepAlivesResumesParkedLogin() = runTest {
        val session = FakeNodeSession().apply { sentInfo = testSentInfo(suggestedTimeoutMs = 1000) }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        runCurrent()

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { service.login(dto.id, password = "pw") }.exceptionOrNull() }
        runCurrent()
        assertEquals(1, service.pendingLoginCount())

        service.stopAllKeepAlives()
        runCurrent()
        job.join()

        assertTrue(thrown is RemoteNodeError.Cancelled)
        assertEquals(0, service.pendingLoginCount())
    }

    // MARK: Logout / disconnect

    @Test
    fun logoutSendsLogoutAndDowngradesToGuest() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()

        service.logout(dto.id)
        runCurrent()

        assertContentEquals(dto.publicKey, session.sendLogoutCalls.single())
        val updated = store.fetchSessionById(dto.id)!!
        assertFalse(updated.isConnected)
        assertEquals(RoomPermissionLevel.GUEST, updated.permissionLevel)
        assertTrue(events.any { it is RemoteNodeEvent.SessionStateChanged && !it.isConnected })
    }

    @Test
    fun logoutIgnoresSendFailure() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession().apply { sendLogoutError = MeshError.NotConnected }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                scope = backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )

        service.logout(dto.id)
        runCurrent()

        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)
        val line = recorded.single { it.message.contains("Logout send failed (ignoring)") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("RemoteNodeService", line.category)
        assertEquals(logoutSendFailedLine("not connected"), line.message)
    }

    @Test
    fun disconnectPreservesPermissionLevel() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)

        service.disconnect(dto.id)

        val updated = store.fetchSessionById(dto.id)!!
        assertFalse(updated.isConnected)
        assertEquals(RoomPermissionLevel.ADMIN, updated.permissionLevel)
    }

    // MARK: Keep-alive

    @Test
    fun manualKeepAliveSendsSyncTimestampWhenDirectRouted() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(lastSyncTimestamp = 1234)
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        service.sendKeepAlive(dto.id)

        val call = session.sendKeepAliveCalls.single()
        assertContentEquals(dto.publicKey, call.first)
        assertEquals(1234L, call.second)
    }

    @Test
    fun manualKeepAliveRecordsCommandAudit() = runTest {
        val recorded = mutableListOf<String>()
        val audit = CommandAuditLogger(record = { recorded += it.message })
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(role = RemoteNodeRole.ROOM_SERVER)
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        val service =
            RemoteNodeService(
                session,
                store,
                InMemoryNodePasswordStore(),
                backgroundScope,
                options = RemoteNodeServiceOptions(auditLogger = audit),
            )

        service.sendKeepAlive(dto.id)

        assertEquals(
            listOf(CommandAuditLogger.Formats.keepAlive(CommandAuditLogger.Target.ROOM, dto.publicKey)),
            recorded,
        )
    }

    @Test
    fun floodRoutedKeepAliveDoesNotRecordCommandAudit() = runTest {
        val recorded = mutableListOf<String>()
        val audit = CommandAuditLogger(record = { recorded += it.message })
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = true)
        val service =
            RemoteNodeService(
                FakeNodeSession(),
                store,
                InMemoryNodePasswordStore(),
                backgroundScope,
                options = RemoteNodeServiceOptions(auditLogger = audit),
            )

        assertFailsWith<RemoteNodeError.FloodRouted> { service.sendKeepAlive(dto.id) }
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun manualKeepAliveThrowsFloodRoutedAndSkipsSend() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = true)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        assertFailsWith<RemoteNodeError.FloodRouted> { service.sendKeepAlive(dto.id) }
        assertTrue(session.sendKeepAliveCalls.isEmpty())
    }

    @Test
    fun manualKeepAliveThrowsContactNotFoundWhenNoContact() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)

        assertFailsWith<RemoteNodeError.ContactNotFound> { service.sendKeepAlive(dto.id) }
    }

    @Test
    fun manualKeepAliveWrapsSeamErrorAsSessionError() = runTest {
        val session = FakeNodeSession().apply { keepAliveError = MeshError.Timeout }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        val error = assertFailsWith<RemoteNodeError.SessionError> { service.sendKeepAlive(dto.id) }
        assertEquals(MeshError.Timeout, error.error)
    }

    @Test
    fun keepAliveLoopDisconnectsNowWhenContactMissing() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        store.saveSession(dto)
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()

        service.startSessionKeepAlive(dto.id, dto.publicKey)
        runCurrent()

        assertTrue(events.any { it is RemoteNodeEvent.SessionStateChanged && !it.isConnected })
        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)
    }

    @Test
    fun keepAliveLoopDisconnectsAfterTwoConsecutiveFailures() = runTest {
        val session = FakeNodeSession().apply { keepAliveError = MeshError.Timeout }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true)
        store.saveSession(dto)
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = false)
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = RemoteNodeService(
            session,
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()

        service.startSessionKeepAlive(dto.id, dto.publicKey)
        runCurrent()
        // First tick is a single transient failure: retry, no disconnect yet.
        assertTrue(events.none { it is RemoteNodeEvent.SessionStateChanged && !it.isConnected })
        runCurrent()
        val retry = recorded.single { it.message.contains("Keep-alive 1/2 failed") }
        assertEquals(DebugLogLevel.WARNING, retry.level)
        assertTrue(retry.message.contains("timeout"))

        advanceTimeBy(91.seconds)
        runCurrent()
        // Second consecutive failure disconnects.
        assertTrue(events.any { it is RemoteNodeEvent.SessionStateChanged && !it.isConnected })
        assertEquals(2, session.sendKeepAliveCalls.size)
        val disconnect = recorded.single { it.message.contains("Keep-alive failed for ${dto.id}") }
        assertEquals(DebugLogLevel.WARNING, disconnect.level)
        assertTrue(disconnect.message.contains("timeout"))
    }
}

/**
 * Status requests, session rows, and password removal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeServiceStatusTest {
    // MARK: Status

    @Test
    fun requestStatusReturnsResponseAndSendsToPublicKey() = runTest {
        val session = FakeNodeSession().apply { statusResponse = testStatusResponse(battery = 3900) }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        val response = service.requestStatus(dto.id)

        assertEquals(3900, response.battery)
        assertContentEquals(dto.publicKey, session.requestStatusCalls.single())
    }

    @Test
    fun requestStatusMapsSeamErrorToSessionError() = runTest {
        val session = FakeNodeSession().apply { statusError = MeshError.DeviceError(5) }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        val error = assertFailsWith<RemoteNodeError.SessionError> { service.requestStatus(dto.id) }
        assertEquals(MeshError.DeviceError(5), error.error)
    }

    @Test
    fun requestStatusTimesOut() = runTest {
        val session = FakeNodeSession().apply { statusDelayMs = 100_000 }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        var thrown: Throwable? = null
        val job = launch { thrown = runCatching { service.requestStatus(dto.id) }.exceptionOrNull() }
        advanceUntilIdle()
        job.join()

        assertTrue(thrown is RemoteNodeError.Timeout)
        assertTrue(session.resetPathCalls.isEmpty(), "status timeout must not flood-reset the companion path")
    }

    @Test
    fun callersDeadlineDoesNotBecomeABinaryQueryTimeout() = runTest {
        val session = FakeNodeSession().apply { statusDelayMs = 100_000 }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)
        var mappedTimeout = false

        withTimeoutOrNull(1.seconds) {
            try {
                service.requestStatus(dto.id, timeout = 10.seconds)
            } catch (_: RemoteNodeError.Timeout) {
                mappedTimeout = true
            }
        }

        assertFalse(mappedTimeout)
    }

    // MARK: Session lifecycle

    @Test
    fun createSessionReusesExistingRowAndCleansDuplicates() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryRemoteNodeStore()
        val existing = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(existing)
        val service = RemoteNodeService(
            FakeNodeSession(),
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )

        val created = service.createSession(existing.radioID, testContact(existing.radioID, existing.publicKey))
        runCurrent()

        // Reuses the existing id (and thus its preserved permission level).
        assertEquals(existing.id, created.id)
        assertEquals(RoomPermissionLevel.ADMIN, created.permissionLevel)
        val hex = "424242424242"
        assertEquals(
            createSessionReusingLine(existing.id, hex, isConnected = true),
            recorded.single {
                it.message.startsWith("createSession: reusing")
            }.message,
        )
        assertEquals(
            createSessionSavedLine(created.id, hex),
            recorded.single {
                it.message.startsWith("createSession: saved")
            }.message,
        )
    }

    @Test
    fun createSessionRecordsCreatingDumpForANewRow() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryRemoteNodeStore()
        val contact = testContact()
        val service = RemoteNodeService(
            FakeNodeSession(),
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )

        val created = service.createSession(contact.radioID, contact)
        runCurrent()

        val hex = "424242424242"
        assertEquals(
            createSessionCreatingLine(hex),
            recorded.single {
                it.message.startsWith("createSession: creating")
            }.message,
        )
        assertEquals(
            createSessionSavedLine(created.id, hex),
            recorded.single {
                it.message.startsWith("createSession: saved")
            }.message,
        )
    }

    @Test
    fun createSessionRejectsChatContact() = runTest {
        val service = RemoteNodeService(
            FakeNodeSession(),
            InMemoryRemoteNodeStore(),
            InMemoryNodePasswordStore(),
            backgroundScope,
        )
        assertFailsWith<RemoteNodeError.InvalidResponse> {
            service.createSession(testUuid(99), testContact(type = ContactType.CHAT))
        }
    }

    @Test
    fun createSessionFetchMissRecordsIosDumpAndThrows() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val contact = testContact()
        val store = object : RemoteNodeStore by InMemoryRemoteNodeStore() {
            override suspend fun fetchSessionByPublicKey(publicKey: ByteArray) = null
        }
        val service = RemoteNodeService(
            FakeNodeSession(),
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )

        assertFailsWith<RemoteNodeError.SessionNotFound> {
            service.createSession(contact.radioID, contact)
        }
        runCurrent()
        val line = recorded.single { it.message.contains("createSession: failed to fetch saved session") }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertEquals("RemoteNodeService", line.category)
    }

    @Test
    fun deviceReportedTimeoutSurfacesAsTheTimeoutError() = runTest {
        val session = FakeNodeSession().apply {
            statusError = MeshError.Timeout
            telemetryError = MeshError.Timeout
        }
        val store = InMemoryRemoteNodeStore()
        val dto = testSession()
        store.saveSession(dto)
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        // A bridge-reported timeout is the same outcome as our own deadline; both must be retryable and
        // localizable, not an opaque session error.
        assertFailsWith<RemoteNodeError.Timeout> { service.requestStatus(dto.id) }
        assertFailsWith<RemoteNodeError.Timeout> { service.requestTelemetry(dto.id) }
        assertTrue(session.resetPathCalls.isEmpty(), "status/telemetry timeout must not flood-reset the companion path")
    }

    @Test
    fun removeSessionDeletesRowAndPassword() = runTest {
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession()
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "pw")
        val service = RemoteNodeService(FakeNodeSession(), store, pw, backgroundScope)

        service.removeSession(dto.id, dto.publicKey)

        assertNull(store.fetchSessionById(dto.id))
        assertFalse(pw.hasPassword(dto.publicKey))
    }
}
