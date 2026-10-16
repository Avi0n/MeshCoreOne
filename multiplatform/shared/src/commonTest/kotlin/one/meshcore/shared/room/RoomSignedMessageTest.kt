package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomSignedMessageTest {
    @Test
    fun authorPrefixFromFirstFourSignatureBytes() {
        val author = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val message = ContactMessage(
            senderPublicKeyPrefix = ByteArray(6) { 1 },
            pathLength = 0,
            textType = TextType.SIGNED_PLAIN.raw,
            senderTimestamp = 1_700_000_000L,
            signature = author + byteArrayOf(0x11, 0x22),
            text = "hello room",
        )
        assertContentEquals(author, authorPrefixFromSignedRoomMessage(message))
    }

    @Test
    fun missingOrShortSignatureYieldsNull() {
        val base = ContactMessage(
            senderPublicKeyPrefix = ByteArray(6) { 1 },
            pathLength = 0,
            textType = TextType.SIGNED_PLAIN.raw,
            senderTimestamp = 1L,
            signature = null,
            text = "x",
        )
        assertNull(authorPrefixFromSignedRoomMessage(base))
        assertNull(authorPrefixFromSignedRoomMessage(base.copy(signature = byteArrayOf(1, 2, 3))))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun missingPrefixRecordsTheIosDropDump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        var handled = false
        ingestSignedRoomMessage(
            message = signedMessage(signature = null),
            logger = PersistentLogger(
                scope = this,
                category = "SyncCoordinator",
                record = { recorded += it },
            ),
            handle = {
                handled = true
                null
            },
            onSaved = {},
        )
        assertEquals(false, handled)
        val line = recorded.single { it.message == DROPPING_SIGNED_MESSAGE_INVALID_PREFIX }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("SyncCoordinator", line.category)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun persistFaultRecordsFailedToHandleRoomMessageAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        ingestSignedRoomMessage(
            message = signedMessage(signature = byteArrayOf(1, 2, 3, 4)),
            logger = PersistentLogger(
                scope = this,
                category = "SyncCoordinator",
                record = { recorded += it },
            ),
            handle = { error("db locked") },
            onSaved = {},
        )
        val line = recorded.single { it.message.contains(FAILED_TO_HANDLE_ROOM_MESSAGE) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("db locked"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aSavedRowReachesOnSaved() = runTest(UnconfinedTestDispatcher()) {
        val saved = RoomMessageDto(
            id = Uuid.normalized("A0000000-0000-0000-0000-000000000031"),
            sessionID = Uuid.normalized("B0000000-0000-0000-0000-000000000031"),
            authorKeyPrefix = byteArrayOf(1, 2, 3, 4),
            text = "hi",
            timestamp = 1L,
            createdAt = 1.0,
            isFromSelf = false,
            deduplicationKey = "k",
            statusRawValue = 0,
            retryAttempt = 0,
            maxRetryAttempts = 0,
        )
        var notified: RoomMessageDto? = null
        ingestSignedRoomMessage(
            message = signedMessage(signature = byteArrayOf(1, 2, 3, 4)),
            logger = null,
            handle = { saved },
            onSaved = { notified = it },
        )
        assertEquals(saved.id, notified?.id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aCancelledPersistRethrowsWithoutLogging() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("superseded")
        var notified = false
        val thrown = assertFailsWith<CancellationException> {
            ingestSignedRoomMessage(
                message = signedMessage(signature = byteArrayOf(1, 2, 3, 4)),
                logger = PersistentLogger(
                    scope = this,
                    category = "SyncCoordinator",
                    record = { recorded += it },
                ),
                handle = { throw cancelled },
                onSaved = { notified = true },
            )
        }
        assertSame(cancelled, thrown)
        assertEquals(false, notified)
        assertTrue(recorded.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anErrorIsLoggedAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        var notified = false
        ingestSignedRoomMessage(
            message = signedMessage(signature = byteArrayOf(1, 2, 3, 4)),
            logger = PersistentLogger(
                scope = this,
                category = "SyncCoordinator",
                record = { recorded += it },
            ),
            handle = { throw SignedRoomProbeError() },
            onSaved = { notified = true },
        )
        assertEquals(false, notified)
        val line = recorded.single { it.message.contains(FAILED_TO_HANDLE_ROOM_MESSAGE) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("probe"))
    }

    private fun signedMessage(signature: ByteArray?) = ContactMessage(
        senderPublicKeyPrefix = ByteArray(6) { 1 },
        pathLength = 0,
        textType = TextType.SIGNED_PLAIN.raw,
        senderTimestamp = 1L,
        signature = signature,
        text = "x",
    )
}

private class SignedRoomProbeError : Error("probe")
