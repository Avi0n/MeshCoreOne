package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Backlog CLI fan-out (iOS `SyncCoordinator.handleIncomingCLIMessage`): room vs repeater by contact
 * type, firmware-echoed prefix stripped, missing sender dropped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IncomingCliMessageTest {
    @Test
    fun roomContactRoutesToRoomAdminWithPrefixStripped() = runTest {
        val room = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val seen = mutableListOf<Pair<ContactDto, String>>()
        room.setCLIHandler { contact, text -> seen += contact to text }
        val repeater = Harness(backgroundScope).admin
        val contact = testContact(type = ContactType.ROOM)

        handleIncomingCliMessage(testContactMessage("3A|> 22dBm"), contact, room, repeater)
        runCurrent()

        assertEquals(1, seen.size)
        assertEquals(contact.id, seen.single().first.id)
        assertEquals("> 22dBm", seen.single().second)
    }

    @Test
    fun repeaterContactRoutesToRepeaterAdminWithPrefixStripped() = runTest {
        val room = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val roomSeen = mutableListOf<String>()
        room.setCLIHandler { _, text -> roomSeen += text }
        val harness = Harness(backgroundScope)
        val repeaterSeen = mutableListOf<ContactMessage>()
        harness.admin.setCLIHandler { message, _ -> repeaterSeen += message }
        val contact = testContact(type = ContactType.REPEATER)

        handleIncomingCliMessage(testContactMessage("0F|uptime: 5s"), contact, room, harness.admin)
        runCurrent()

        assertTrue(roomSeen.isEmpty())
        assertEquals(1, repeaterSeen.size)
        assertEquals("uptime: 5s", repeaterSeen.single().text)
    }

    @Test
    fun chatContactUsesRepeaterAdminElseBranch() = runTest {
        val room = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val harness = Harness(backgroundScope)
        val repeaterSeen = mutableListOf<String>()
        harness.admin.setCLIHandler { message, _ -> repeaterSeen += message.text }

        handleIncomingCliMessage(
            testContactMessage("plain reply"),
            testContact(type = ContactType.CHAT),
            room,
            harness.admin,
        )
        runCurrent()

        assertEquals(listOf("plain reply"), repeaterSeen)
    }

    @Test
    fun missingContactIsDroppedAndLogged() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(backgroundScope, category = "test") { recorded += it }
        val room = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val harness = Harness(backgroundScope)
        val repeaterSeen = mutableListOf<String>()
        harness.admin.setCLIHandler { message, _ -> repeaterSeen += message.text }

        handleIncomingCliMessage(testContactMessage("orphan"), contact = null, room, harness.admin, logger)
        runCurrent()

        assertTrue(repeaterSeen.isEmpty())
        assertEquals(DROPPING_CLI_RESPONSE_NO_CONTACT, recorded.single().message)
    }

    private class Harness(scope: kotlinx.coroutines.CoroutineScope) {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(session, store, passwordStore, scope)
        val cli = NodeCliCoordinator(session, store, passwordStore, scope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
    }
}
