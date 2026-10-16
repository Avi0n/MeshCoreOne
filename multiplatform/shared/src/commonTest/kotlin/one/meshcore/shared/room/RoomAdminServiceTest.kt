package one.meshcore.shared.room

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Status/telemetry delegation, admin-gated CLI, and the handler-slot lifecycle for [RoomAdminService],
 * driven against a [FakeRoomAdminSession] and [InMemoryRoomStore]. Covers the role gating (a non-admin
 * cannot issue configuration commands) and the `clearStatusHandlers` split that keeps a settings CLI
 * handler alive when a status segment tears down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomAdminServiceTest {
    // MARK: Status / telemetry

    @Test
    fun requestStatusReturnsTheRoomFrameUntouched() = runTest {
        val store = InMemoryRoomStore()
        store.seedSession(roomSession())
        val fake = FakeRoomAdminSession().apply { statusResponse = roomStatus(posted = 42, pushed = 7) }
        val service = RoomAdminService(fake, store)

        val status = service.requestStatus(SESSION_ID)

        // The bridge decodes the room-server layout, so nothing above it may re-read the frame.
        assertSame(fake.statusResponse, status)
        assertEquals("roomServer", status.layout)
        assertEquals(42, status.roomServerPostedCount)
        assertEquals(7, status.roomServerPostPushCount)
        assertContentEquals(testPublicKey(), fake.statusCalls.single())
    }

    @Test
    fun requestTelemetryDelegates() = runTest {
        val store = InMemoryRoomStore()
        store.seedSession(roomSession())
        val fake = FakeRoomAdminSession()
        val service = RoomAdminService(fake, store)

        service.requestTelemetry(SESSION_ID)
        assertContentEquals(testPublicKey(), fake.telemetryCalls.single())
    }

    @Test
    fun requestStatusUnknownSessionThrows() = runTest {
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        assertFailsWith<RoomServerError.SessionNotFound> { service.requestStatus(SESSION_ID) }
    }

    // MARK: Admin gating

    @Test
    fun sendCommandRequiresAdmin() = runTest {
        val store = InMemoryRoomStore()
        store.seedSession(roomSession(permissionLevel = RoomPermissionLevel.READ_WRITE))
        val fake = FakeRoomAdminSession()
        val service = RoomAdminService(fake, store)

        assertFailsWith<RemoteNodeError.PermissionDenied> {
            service.sendCommand(SESSION_ID, RoomSettingCommand.GET_GUEST_PASSWORD)
        }
        assertTrue(fake.commandCalls.isEmpty())
    }

    @Test
    fun sendCommandAllowedForAdmin() = runTest {
        val store = InMemoryRoomStore()
        store.seedSession(roomSession(permissionLevel = RoomPermissionLevel.ADMIN))
        val fake = FakeRoomAdminSession().apply { commandResponse = "ok" }
        val service = RoomAdminService(fake, store)

        val response = service.sendRawCommand(SESSION_ID, RoomSettingCommand.setAllowReadOnly(true))
        assertEquals("ok", response)
        assertEquals("set allow.read.only on", fake.rawCommandCalls.single().second)
    }

    // MARK: Handler slots

    @Test
    fun invokeHandlersDispatchToRegistered() = runTest {
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        var status = 0
        var telemetry = 0
        var cli = ""
        service.setStatusHandler { status++ }
        service.setTelemetryHandler { telemetry++ }
        service.setCLIHandler { _, text -> cli = text }

        service.invokeStatusHandler(roomStatus())
        service.invokeTelemetryHandler(roomTelemetry())
        service.invokeCLIHandler(cliContact(), "late reply")

        assertEquals(1, status)
        assertEquals(1, telemetry)
        assertEquals("late reply", cli)
    }

    @Test
    fun invokeHandlersRecordCommandAudit() = runTest {
        val recorded = mutableListOf<String>()
        val audit = CommandAuditLogger(record = { recorded += it.message })
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore(), auditLogger = audit)
        val status = roomStatus()
        val telemetry = roomTelemetry()
        val contact = cliContact()

        service.invokeStatusHandler(status)
        service.invokeTelemetryHandler(telemetry)
        service.invokeCLIHandler(contact, "late reply")

        assertEquals(
            listOf(
                CommandAuditLogger.Formats.statusResponse(
                    CommandAuditLogger.Target.ROOM,
                    status.publicKeyPrefix,
                    batteryMv = status.battery,
                    uptimeSec = status.uptime,
                ),
                CommandAuditLogger.Formats.telemetryResponse(
                    CommandAuditLogger.Target.ROOM,
                    telemetry.publicKeyPrefix,
                    pointCount = telemetry.points.size,
                ),
                CommandAuditLogger.Traffic.cliResponse(contact.publicKey, "late reply"),
            ),
            recorded,
        )
    }

    @Test
    fun clearStatusHandlersKeepsCliHandler() = runTest {
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        var status = 0
        var cli = 0
        service.setStatusHandler { status++ }
        service.setTelemetryHandler { }
        service.setCLIHandler { _, _ -> cli++ }

        service.clearStatusHandlers()
        service.invokeStatusHandler(roomStatus())
        service.invokeCLIHandler(cliContact(), "still here")

        assertEquals(0, status)
        assertEquals(1, cli)
    }

    @Test
    fun clearHandlersDropsEverything() = runTest {
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        var cli = 0
        service.setCLIHandler { _, _ -> cli++ }

        service.clearHandlers()
        service.invokeCLIHandler(cliContact(), "gone")
        assertEquals(0, cli)
    }

    @Test
    fun missingHandlersStayOffDiskAndStillAudit() = runTest {
        val debug = mutableListOf<DebugLogEntryDto>()
        val audit = mutableListOf<String>()
        val service = RoomAdminService(
            FakeRoomAdminSession(),
            InMemoryRoomStore(),
            auditLogger = CommandAuditLogger(record = { audit += it.message }),
            logger = PersistentLogger(backgroundScope, category = "RoomAdmin", record = { debug += it }),
        )
        service.invokeStatusHandler(roomStatus())
        service.invokeTelemetryHandler(roomTelemetry())
        service.invokeCLIHandler(cliContact(), "gone")
        runCurrent()

        assertTrue(debug.isEmpty())
        assertEquals(3, audit.size)
        assertTrue(audit.any { it.contains("STATUS") })
        assertTrue(audit.any { it.contains("TELEM") })
        assertTrue(audit.any { it.contains("CLI_RESP") })
    }

    private fun cliContact(): ContactDto = ContactDto(
        id = testUuid(50),
        radioID = testUuid(99),
        publicKey = testPublicKey(),
        name = "Room",
        typeRawValue = ContactType.ROOM.raw,
        flags = 0,
        outPathLength = 0,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    private companion object {
        val SESSION_ID: Uuid = testUuid(1)
    }
}
