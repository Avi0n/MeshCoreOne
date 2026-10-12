// AndroidOnly: WP-307 Real Room and mounted retry/Send Again durability and generation regressions.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineDependencies
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineRetryService
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DurableRetryIntegrationTest : RoomProcessTest() {
    private val radio = RadioId(UUID.fromString("447175B7-557D-4F48-B671-4B2CEE2DC932"))
    private val otherRadio = RadioId(UUID.fromString("547175B7-557D-4F48-B671-4B2CEE2DC932"))
    private val peerKey = key(3)
    private fun messageKey(message: MessageDTO) = EntityKey(message.radioId, message.id)
    private fun isSend(frame: Bytes) = frame[0] == 0x02.toUByte()

    private suspend fun seed(): Pair<ContactDTO, MessageDTO> {
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Restored", isActive = false))
        val contact = ContactDTO(radioId = radio, publicKey = peerKey, name = "Peer", lastHeardTimestamp = null)
        store.saveContact(contact)
        val message = MessageDTO(
            radioId = radio, contactID = contact.id, text = "durable retry",
            timestamp = EPOCH.epochSecond.toUInt(), createdAt = EPOCH, sortDate = EPOCH,
            direction = MessageDirection.OUTGOING, status = MessageStatus.FAILED,
        )
        store.saveMessage(message)
        return contact to message
    }

    private suspend fun seedPending(contact: ContactDTO, message: MessageDTO): PendingSendDTO {
        val dto = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), contact.radioId)
        return dto.copy(sequence = store.insertPendingSendAssigningSequence(dto))
    }

    private suspend fun timeline(harness: ContainerHarness, contact: ContactDTO): ChatViewModel {
        val conversation = TimelineConversation.Direct(contact)
        return ChatViewModel(
            conversation, harness.container.chatsFeature.timeline(conversation),
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()),
        ).also { it.open() }
    }

    private fun configure(harness: ContainerHarness, deliver: Boolean) {
        harness.links.configureRadio = { fake ->
            fake.knownContacts += contactFrame(peerKey, "Peer")
            fake.respond = { frame ->
                if (isSend(frame)) {
                    if (deliver) sentAndDelivered(frame, key(7)) else emptyList()
                } else null
            }
        }
    }

    @Test fun offlineMountedRetryReplacesRowsBeforeQueueLookupAndRetainsPendingTimeline() = runTest {
        val (contact, message) = seed()
        val old = listOf(seedPending(contact, message), seedPending(contact, message))
        store.saveDevice(DeviceDTO(radioId = otherRadio, publicKey = key(8), nodeName = "Other"))
        val otherContact = contact.copy(radioId = otherRadio)
        val otherMessage = message.copy(radioId = otherRadio)
        store.saveContact(otherContact)
        store.saveMessage(otherMessage)
        val untouched = seedPending(otherContact, otherMessage)
        val harness = ContainerHarness(this, store)
        try {
            val viewModel = timeline(harness, contact)
            viewModel.retry(message.id)
            val pending = store.fetchPendingSendsForMessage(messageKey(message))
            assertEquals(1, pending.size)
            val saved = pending.single()
            assertTrue(old.none { it.id == saved.id })
            assertFalse(saved.isResend)
            assertEquals(DirectMessageEnvelope(message.id, contact.id, false), saved.directMessageEnvelope())
            assertEquals(0L, saved.attemptCount)
            assertEquals(MessageStatus.PENDING, assertNotNull(store.fetchMessage(messageKey(message))).status)
            assertEquals(MessageStatus.PENDING, viewModel.state.value.messages.single().status)
            assertEquals(ChatSendQueueServiceError.NotConnected,
                assertIs<ChatSendQueueServiceException>(viewModel.state.value.sendError).error)
            assertTrue(viewModel.state.value.retryingMessageIds.isEmpty())
            assertEquals(listOf(untouched), store.fetchPendingSendsForMessage(messageKey(otherMessage)))
            assertEquals(MessageStatus.FAILED, assertNotNull(store.fetchMessage(messageKey(otherMessage))).status)
            assertTrue(harness.links.radios.isEmpty())
        } finally { harness.container.close() }
    }

    @Test fun deliberateSendAgainPersistsResendTrueAndNeverDowngradesLateDelivery() = runTest {
        val (contact, message) = seed()
        val harness = ContainerHarness(this, store)
        try {
            val viewModel = timeline(harness, contact)
            store.updateMessageStatus(messageKey(message), MessageStatus.DELIVERED)
            viewModel.retry(message.id)
            assertFalse(store.fetchPendingSendsForMessage(messageKey(message)).single().isResend)
            assertEquals(MessageStatus.DELIVERED, viewModel.state.value.messages.single().status)
            assertFailsWith<ChatSendQueueServiceException> {
                harness.container.chatsFeature.sendAgain(message, TimelineConversation.Direct(contact))
            }
            assertTrue(store.fetchPendingSendsForMessage(messageKey(message)).single().isResend)
            assertEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(messageKey(message))).status)
        } finally { harness.container.close() }
    }

    @Test fun cancelledCallerAfterRealMountedCommitRetainsPendingAndTheSameCancellation() = runTest {
        val (contact, message) = seed()
        val harness = ContainerHarness(this, store)
        try {
            val conversation = TimelineConversation.Direct(contact)
            val mounted = harness.container.chatsFeature.timeline(conversation)
            val cancellation = CancellationException("cancel at post-commit queue boundary")
            val dependencies = object : ChatTimelineDependencies by mounted {
                override val retryService = TimelineRetryService { row, target ->
                    try {
                        mounted.retryService.retry(row, target)
                    } catch (offline: ChatSendQueueServiceException) {
                        assertEquals(ChatSendQueueServiceError.NotConnected, offline.error)
                        assertEquals(1, store.fetchPendingSendsForMessage(messageKey(message)).size)
                        assertNotNull(currentCoroutineContext()[Job]).cancel(cancellation)
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            val viewModel = ChatViewModel(conversation, dependencies, backgroundScope)
            viewModel.open()
            var observed: CancellationException? = null
            val caller = launch {
                try { viewModel.retry(message.id) }
                catch (failure: CancellationException) { observed = failure }
            }
            caller.join()
            assertTrue(caller.isCancelled)
            assertSame(cancellation, observed)
            assertEquals(MessageStatus.PENDING, viewModel.state.value.messages.single().status)
            assertTrue(viewModel.state.value.retryingMessageIds.isEmpty())
            assertEquals(MessageStatus.PENDING, assertNotNull(store.fetchMessage(messageKey(message))).status)
            assertFalse(store.fetchPendingSendsForMessage(messageKey(message)).single().isResend)
        } finally { harness.container.close() }
    }

    @Test fun realTransactionFailureRestoresOldRowAndFailedTimelineWithoutQueueAdmission() = runTest {
        val (contact, message) = seed()
        val prior = seedPending(contact, message)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_manual_retry BEFORE INSERT ON pending_sends BEGIN SELECT RAISE(ABORT, 'retry fixture'); END",
        )
        val harness = ContainerHarness(this, store)
        try {
            val viewModel = timeline(harness, contact)
            viewModel.retry(message.id)
            assertIs<PersistenceStoreException>(viewModel.state.value.sendError)
            assertEquals(listOf(prior), store.fetchPendingSendsForMessage(messageKey(message)))
            assertEquals(MessageStatus.FAILED, assertNotNull(store.fetchMessage(messageKey(message))).status)
            assertEquals(MessageStatus.FAILED, viewModel.state.value.messages.single().status)
            assertTrue(viewModel.state.value.retryingMessageIds.isEmpty())
            assertTrue(harness.links.radios.isEmpty())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_manual_retry")
            harness.container.close()
        }
    }

    @Test fun offlineIntentHydratesThroughActualNewGenerationWithoutDoublePersistence() = runTest {
        val (contact, message) = seed()
        val harness = ContainerHarness(this, store)
        configure(harness, deliver = true)
        try {
            val viewModel = timeline(harness, contact)
            viewModel.retry(message.id)
            assertEquals(1, store.fetchPendingSendsForMessage(messageKey(message)).size)
            harness.manager.connect(harness.target())
            harness.eventually("ready hydrated generation") { harness.manager.connectionState == DeviceConnectionState.READY }
            harness.eventually("hydrated DM delivered") {
                kotlinx.coroutines.runBlocking {
                    store.fetchMessage(messageKey(message))?.status == MessageStatus.DELIVERED &&
                        store.fetchPendingSendsForMessage(messageKey(message)).isEmpty()
                }
            }
            assertEquals(1, harness.links.radios.single().sent.count(::isSend))
            assertEquals(0, assertNotNull(harness.sessions.current).chatSendQueueService.queuedEnvelopeCount)
        } finally { harness.container.close() }
    }

    @Test fun joinedOldGenerationLeavesOneDurableIntentForReplacementHydration() = runTest {
        val (contact, message) = seed()
        val harness = ContainerHarness(this, store)
        configure(harness, deliver = false)
        val target = harness.target()
        try {
            harness.manager.connect(target)
            harness.eventually("first generation ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val old = assertNotNull(harness.sessions.current)
            val viewModel = timeline(harness, contact)
            viewModel.retry(message.id)
            harness.eventually("held first send") { harness.links.radios.first().sent.any(::isSend) }
            assertEquals(1, store.fetchPendingSendsForMessage(messageKey(message)).size)
            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertTrue(old.isTornDown)
            assertFalse(old.chatSendQueueService.isObservingConnection)
            assertEquals(0, harness.links.radios.first().readers)
            assertEquals(1, store.fetchPendingSendsForMessage(messageKey(message)).size)
            configure(harness, deliver = true)
            harness.manager.connect(target)
            harness.eventually("replacement ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val replacement = assertNotNull(harness.sessions.current)
            assertTrue(replacement.token != old.token)
            harness.eventually("replacement delivered retained intent") {
                kotlinx.coroutines.runBlocking {
                    store.fetchMessage(messageKey(message))?.status == MessageStatus.DELIVERED &&
                        store.fetchPendingSendsForMessage(messageKey(message)).isEmpty()
                }
            }
            assertEquals(1, harness.links.radios.first().sent.count(::isSend))
            assertEquals(1, harness.links.radios.last().sent.count(::isSend))
        } finally { harness.container.close() }
    }
}
