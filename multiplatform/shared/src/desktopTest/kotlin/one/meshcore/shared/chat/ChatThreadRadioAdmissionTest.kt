package one.meshcore.shared.chat

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatThreadRadioAdmissionTest {
    @Test
    fun aCachedChannelOnAnotherReadyRadioCannotCreateOrEnqueueAMessage() = withFixture(channel = true) {
        it.send()
        assertNoAdmission(it.snapshot("foreign-channel-send"))
    }

    @Test
    fun aCachedDirectThreadOnAnotherReadyRadioCannotCreateOrEnqueueAMessage() = withFixture(channel = false) {
        it.send()
        assertNoAdmission(it.snapshot("foreign-direct-send"))
    }

    @Test
    fun aChannelOnTheMatchingRadioPersistsTheOriginalPartition() = withFixture(channel = true, matching = true) {
        it.send()
        assertMatchingAdmission(it.snapshot("matching-channel-send"), channel = true)
    }

    @Test
    fun aDirectThreadOnTheMatchingRadioPersistsTheOriginalPartition() = withFixture(channel = false, matching = true) {
        it.send()
        assertMatchingAdmission(it.snapshot("matching-direct-send"), channel = false)
    }

    @Test
    fun aForeignChannelRetryLeavesTheFailedRowAndQueuesUntouched() = rejectRetry(channel = true, resend = false)

    @Test
    fun aForeignDirectRetryLeavesTheFailedRowAndQueuesUntouched() = rejectRetry(channel = false, resend = false)

    @Test
    fun aForeignChannelSendAgainLeavesTheFailedRowAndQueuesUntouched() = rejectRetry(channel = true, resend = true)

    @Test
    fun aForeignDirectSendAgainLeavesTheFailedRowAndQueuesUntouched() = rejectRetry(channel = false, resend = true)

    @Test
    fun aChannelOwnerSwitchAfterRoomCommitFailsTheOriginalRowWithoutEnqueue() = rejectCommittedSend(
        channel = true,
        sameRadio = false,
    )

    @Test
    fun aDirectOwnerSwitchAfterRoomCommitFailsTheOriginalRowWithoutEnqueue() = rejectCommittedSend(
        channel = false,
        sameRadio = false,
    )

    @Test
    fun aNewChannelOwnerOnTheSameRadioAfterRoomCommitCannotEnqueueTheOldAction() = rejectCommittedSend(
        channel = true,
        sameRadio = true,
    )

    @Test
    fun aNewDirectOwnerOnTheSameRadioAfterRoomCommitCannotEnqueueTheOldAction() = rejectCommittedSend(
        channel = false,
        sameRadio = true,
    )

    @Test
    fun aForeignChannelSendAgainPreservesTheAlreadySentRow() = rejectSentResend(channel = true)

    @Test
    fun aForeignDirectSendAgainPreservesTheAlreadySentRow() = rejectSentResend(channel = false)

    @Test
    fun aMatchingChannelSendAgainPersistenceFailurePreservesTheAlreadySentRow() =
        withFixture(channel = true, matching = true) { fixture ->
            val id = fixture.seedSentMessage()
            val before = fixture.snapshot("before-matching-sent-channel-resend")
            val failure = fixture.failNextQueuePersist()
            fixture.retry(id, isResend = true)
            assertTrue(failure.isCompleted, "the matching action must reach the controlled queue write")
            val after = fixture.snapshot("after-matching-sent-channel-persist-failure")
            assertEquals(before.messages, after.messages, "an already-sent packet must stay sent")
            assertTrue(after.pending.isEmpty())
            assertEquals(0, after.sessionWrites)
            assertEquals(ChatErrorMessages.developerEnglish().sendQueuePersistFailed, after.sendErrorMessage)
        }

    private fun rejectCommittedSend(channel: Boolean, sameRadio: Boolean) =
        withFixture(channel = channel, matching = true) { fixture ->
            val commit = fixture.holdNextMessageCommit()
            val send = fixture.beginSend()
            try {
                val saved = commit.savedEntered.await()
                val before = fixture.snapshot("held-commit-channel=$channel-sameRadio=$sameRadio")
                assertEquals(listOf(saved.id), before.messages.map { it.id })
                assertEquals(MessageStatus.PENDING, before.messages.single().status)
                assertTrue(before.pending.isEmpty())
                fixture.replaceLiveOwner(sameRadio)
                commit.release.complete(Unit)
                send.await()
                val after = fixture.snapshot("released-commit-channel=$channel-sameRadio=$sameRadio")
                assertTrue(after.pending.isEmpty(), "neither queue may admit a superseded action")
                val failed = after.messages.single()
                assertEquals(saved.id, failed.id)
                assertEquals(ChatThreadRadioAdmissionFixture.RADIO_A, failed.radioID)
                assertEquals(ChatThreadRadioAdmissionFixture.TEST_TEXT, failed.text)
                assertEquals(MessageStatus.FAILED, failed.status)
                assertEquals(ChatErrorMessages.developerEnglish().unableToSend, after.sendErrorMessage)
                assertEquals(0, after.sessionWrites)
            } finally {
                commit.release.complete(Unit)
                send.cancelAndJoin()
            }
        }

    private fun rejectSentResend(channel: Boolean) = withFixture(channel = channel) { fixture ->
        val id = fixture.seedSentMessage()
        val before = fixture.snapshot("before-foreign-sent-resend-channel=$channel")
        assertEquals(MessageStatus.SENT, before.messages.single().status)
        fixture.retry(id, isResend = true)
        val after = fixture.snapshot("after-foreign-sent-resend-channel=$channel")
        assertEquals(before.messages, after.messages)
        assertTrue(after.pending.isEmpty())
        assertEquals(0, after.sessionWrites)
    }

    private fun rejectRetry(channel: Boolean, resend: Boolean) = withFixture(channel = channel) { fixture ->
        val id = fixture.seedFailedMessage()
        val before = fixture.snapshot("before-retry-channel=$channel-resend=$resend")
        assertEquals(MessageStatus.FAILED, before.messages.single().status)
        fixture.retry(id, isResend = resend)
        val after = fixture.snapshot("after-retry-channel=$channel-resend=$resend")
        assertEquals(before.messages, after.messages, "rejected retry must not mutate the original A row")
        assertTrue(after.pending.isEmpty(), "neither partition may acquire a retry envelope")
        assertEquals(0, after.sessionWrites)
    }

    private fun assertNoAdmission(snapshot: ChatThreadRadioAdmissionFixture.Snapshot) {
        assertTrue(snapshot.messages.isEmpty(), "no optimistic row may be written through a foreign live graph")
        assertTrue(snapshot.pending.isEmpty(), "neither radio may receive a durable envelope")
        assertEquals(0, snapshot.sessionWrites)
    }

    private fun assertMatchingAdmission(snapshot: ChatThreadRadioAdmissionFixture.Snapshot, channel: Boolean) {
        val message = snapshot.messages.single()
        val pending = snapshot.pending.single()
        assertEquals(ChatThreadRadioAdmissionFixture.RADIO_A, message.radioID)
        assertEquals(MessageStatus.PENDING, message.status)
        assertEquals(ChatThreadRadioAdmissionFixture.RADIO_A, pending.radioID)
        assertEquals(message.id, pending.messageID)
        if (channel) {
            assertEquals(ChatThreadRadioAdmissionFixture.CHANNEL_INDEX, message.channelIndex)
            assertEquals(message.channelIndex, pending.channelIndex)
        } else {
            assertEquals(ChatFixtures.CONTACT, message.contactID)
            assertEquals(message.contactID, pending.contactID)
        }
        assertEquals(0, snapshot.sessionWrites, "the fixture holds real queue preflight before any writer")
    }

    private fun withFixture(
        channel: Boolean,
        matching: Boolean = false,
        block: suspend (ChatThreadRadioAdmissionFixture) -> Unit,
    ) = runBlocking {
        val fixture = ChatThreadRadioAdmissionFixture()
        try {
            withTimeout(ChatThreadRadioAdmissionFixture.TIMEOUT_MS) {
                fixture.configure(isChannel = channel, matchingRadio = matching)
                block(fixture)
            }
        } finally {
            fixture.close()
        }
    }
}
