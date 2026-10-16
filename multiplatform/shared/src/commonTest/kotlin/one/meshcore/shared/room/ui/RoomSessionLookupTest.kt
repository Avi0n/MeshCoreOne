package one.meshcore.shared.room.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.room.roomContact
import one.meshcore.shared.room.roomSession
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomSessionLookupTest {
    @Test
    fun aPersistFaultOnRoomAuthLandsNull() = runTest {
        val contact = loadRoomAuthContact(
            fetchSession = { error("db locked") },
            fetchContact = { roomContact() },
        )
        assertNull(contact)
    }

    @Test
    fun aPersistFaultOnRoomAuthContactLandsNull() = runTest {
        val session = roomSession()
        val contact = loadRoomAuthContact(
            fetchSession = { session },
            fetchContact = { error("db locked") },
        )
        assertNull(contact)
    }

    @Test
    fun aSuccessfulRoomAuthReturnsTheContact() = runTest {
        val session = roomSession()
        val expected = roomContact()
        val contact = loadRoomAuthContact(
            fetchSession = { session },
            fetchContact = { current ->
                assertEquals(session.id, current.id)
                expected
            },
        )
        assertSame(expected, contact)
    }

    @Test
    fun aMissingContactOpensAuthAsNotFound() {
        assertTrue(roomConversationAuthShowsNotFound(null))
        assertFalse(roomConversationAuthShowsNotFound(roomContact()))
    }

    @Test
    fun missingAuthContactUsesWarningTriangle() {
        assertEquals(AppIcons.Warning, ROOM_CONVERSATION_AUTH_MISSING_ICON)
    }

    @Test
    fun aMissingSessionLandsNull() = runTest {
        val contact = loadRoomAuthContact(
            fetchSession = { null },
            fetchContact = { roomContact() },
        )
        assertNull(contact)
    }

    @Test
    fun aCancelledRoomAuthPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> {
                loadRoomAuthContact(
                    fetchSession = { throw cancelled },
                    fetchContact = { roomContact() },
                )
            },
        )
    }

    @Test
    fun aRoomAuthErrorPropagates() = runTest {
        val probe = RoomSessionProbeError()
        assertSame(
            probe,
            assertFailsWith<RoomSessionProbeError> {
                loadRoomAuthContact(
                    fetchSession = { throw probe },
                    fetchContact = { roomContact() },
                )
            },
        )
    }

    @Test
    fun aPersistFaultOnConnectedSessionLandsNull() = runTest {
        assertNull(loadConnectedSession { error("db locked") })
    }

    @Test
    fun aCancelledConnectedSessionPropagates() = runTest {
        val cancelled = CancellationException("superseded")
        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { loadConnectedSession { throw cancelled } },
        )
    }

    @Test
    fun aConnectedSessionErrorPropagates() = runTest {
        val probe = RoomSessionProbeError()
        assertSame(probe, assertFailsWith<RoomSessionProbeError> { loadConnectedSession { throw probe } })
    }

    @Test
    fun aSuccessfulConnectedSessionReturnsTheRow() = runTest {
        val session = roomSession()
        assertSame(session, loadConnectedSession { session })
    }
}

private class RoomSessionProbeError : Error("probe")
