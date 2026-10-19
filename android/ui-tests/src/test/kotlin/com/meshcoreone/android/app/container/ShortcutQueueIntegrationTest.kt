// AndroidOnly: WP-502 Verify the real shortcut flow and AppContainer durable queue over Room.
// ReferenceInput: MC1/Intents/SendMessageIntent.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Intents/SendAdvertIntent.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Intents/IntentError.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Extensions/Errors/MeshCoreError+UserFacingMessage.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MeshCore/Sources/MeshCore/Protocol/Parsers+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.l10n.R as L10nR
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.contacts.AdvertisementError
import com.meshcoreone.android.platform.shortcuts.*
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ShortcutQueueIntegrationTest : RoomProcessTest() {
    private val peerKey = key(4)
    private val original = "Shortcut original \u6d4b\u8bd5 \u0645\u0631\u062d\u0628\u0627"
    private val text = AndroidShortcutText(ApplicationProvider.getApplicationContext())
    private val target = UUID.fromString("50200000-0000-0000-0000-000000000217")

    private fun configure(h: ContainerHarness, holdSync: Boolean = false, rejectSend: Boolean = false) {
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(peerKey, "Synthetic shortcut peer")
            firmware.respond = { frame ->
                when {
                    frame[0].toInt() == 4 && holdSync -> emptyList()
                    frame[0].toInt() == 0x1f && frame[1].toInt() == 0 ->
                        listOf(Bytes.of(0x12, 0) + Bytes.utf8("Synthetic channel").paddedOrTruncated(32) + Bytes(ByteArray(16) { 1 }))
                    // notFound (2) is terminal for direct sends; channel illegalArgument (6) is terminal.
                    frame[0].toInt() in 2..3 -> if (rejectSend) listOf(Bytes.of(1, if (frame[0].toInt() == 2) 2 else 6)) else emptyList()
                    else -> null
                }
            }
        }
    }

    private suspend fun connected(
        test: TestScope, backing: RoomPersistenceStore = store,
        identity: (ConnectionTarget) -> Bytes = { key(7) },
        rejectSend: Boolean = false,
    ): ContainerHarness {
        val h = ContainerHarness(test, backing, identity = identity)
        configure(h, rejectSend = rejectSend)
        h.manager.connect(h.target(target))
        h.eventually("shortcut services ready") { h.manager.connectionState == DeviceConnectionState.READY }
        val radio = assertNotNull(h.appState.currentRadioId)
        if (backing.fetchChannels(radio).none { it.index == 0.toUByte() }) {
            backing.saveChannel(ChannelDTO(radioId = radio, index = 0u, name = "Synthetic channel"))
        }
        return h
    }

    private suspend fun recipients(h: ContainerHarness, backing: RoomPersistenceStore = store): List<ShortcutRecipient> {
        val radio = assertNotNull(h.appState.currentRadioId)
        return listOf(
            ShortcutRecipient.Contact(assertNotNull(backing.fetchContact(radio, peerKey))),
            ShortcutRecipient.Channel(backing.fetchChannels(radio).single { it.index == 0.toUByte() }),
        )
    }

    private fun id(recipient: ShortcutRecipient): String = when (recipient) {
        is ShortcutRecipient.Contact -> TargetIdentity.contactId(recipient.dto)
        is ShortcutRecipient.Channel -> TargetIdentity.channelId(recipient.dto)
    }

    private suspend fun messages(backing: RoomPersistenceStore, recipient: ShortcutRecipient): List<MessageDTO> = when (recipient) {
        is ShortcutRecipient.Contact -> backing.fetchMessages(EntityKey(recipient.radioId, recipient.dto.id), 100, 0)
        is ShortcutRecipient.Channel -> backing.fetchMessages(recipient.radioId, recipient.dto.index, 100, 0)
    }

    private fun flow(h: ContainerHarness, confirmAction: suspend (ConfirmationRequest) -> Boolean = { true }) =
        SendMessageFlow(AppShortcutRadio(h.container), object : ShortcutConfirmationPort {
            override suspend fun confirm(request: ConfirmationRequest) = confirmAction(request)
        }, text)

    @Test fun realAdvertServiceSessionErrorsReachAdapterAndExecutorForZeroHopAndFlood() = runTest {
        val h = connected(this)
        val adapter = AppShortcutRadio(h.container)
        val firmware = h.links.radios.single()
        val originalResponse = firmware.respond
        var reject = true
        firmware.respond = { frame ->
            if (frame[0].toInt() == 7) listOf(if (reject) Bytes.of(1, 6) else Bytes.of(0))
            else originalResponse(frame)
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = context.getString(L10nR.string.l10n_app_localizable_error_device_invalidparameter)
        try {
            for (reach in AdvertReach.entries) {
                reject = true
                val failure = assertFailsWith<ShortcutException> { adapter.sendAdvert(reach.sendsFlood) }
                val outer = assertIs<AdvertisementError.SessionError>(failure.cause)
                val payload = assertIs<MeshCoreException.DeviceError>(outer.error)
                assertEquals(6.toUByte(), payload.code)
                assertSame(payload, assertIs<ShortcutError.SessionError>(failure.error).underlying)
                assertEquals(expected, text.error(failure.error))
                val shown = mutableListOf<String>()
                val confirmations = mutableListOf<ConfirmationRequest>()
                val executor = ShortcutRequestExecutor(
                    adapter,
                    object : ShortcutConfirmationPort {
                        override suspend fun confirm(request: ConfirmationRequest): Boolean {
                            confirmations += request
                            return true
                        }
                    },
                    text,
                    object : ShortcutPresenter {
                        override fun show(message: String) { shown += message }
                        override fun openApp(request: ShortcutRequest) { fail("Advert failure must not escalate") }
                    },
                )
                executor.run(ShortcutRequest.Advert(reach))
                assertEquals(listOf(expected), shown)
                assertEquals(if (reach.sendsFlood) 1 else 0, confirmations.size)
                reject = false
                executor.run(ShortcutRequest.Advert(reach))
                assertEquals(listOf(expected, text.advertSent(reach)), shown)
            }
            assertEquals(
                List(3) { Bytes.of(7) } + List(3) { Bytes.of(7, 1) },
                firmware.sent.filter { it[0].toInt() == 7 },
            )
            assertTrue(store.fetchPendingSends(assertNotNull(h.appState.currentRadioId)).isEmpty())
            for (recipient in recipients(h)) assertTrue(messages(store, recipient).isEmpty())
            assertEquals(1, h.lifecycle.created.size)
        } finally { h.container.close() }
    }

    @Test fun realAdvertBoundaryPreservesTypedNotConnectedFallbackAndCancellationForBothReaches() = runTest {
        for (reach in AdvertReach.entries) {
            val cancellation = CancellationException("cancel actual advert boundary")
            val failures = listOf(
                AdvertisementError.NotConnected() to ShortcutError.NOT_CONNECTED,
                AdvertisementError.SendFailed() to ShortcutError.ADVERT_FAILED,
                AdvertisementError.InvalidResponse() to ShortcutError.ADVERT_FAILED,
                IllegalStateException("synthetic transport failure") to ShortcutError.ADVERT_FAILED,
                cancellation to null,
            )
            for ((originalFailure, expected) in failures) {
                // Each injected transport-write failure leaves correlation uncertain; use a new real generation.
                val h = connected(this)
                val firmware = h.links.radios.single()
                val originalResponse = firmware.respond
                firmware.respond = { frame ->
                    if (frame[0].toInt() == 7) throw originalFailure else originalResponse(frame)
                }
                try {
                    val adapter = AppShortcutRadio(h.container)
                    if (originalFailure === cancellation) {
                        val actual = assertFailsWith<CancellationException> { adapter.sendAdvert(reach.sendsFlood) }
                        assertTrue(actual === cancellation || actual.cause === cancellation)
                    } else {
                        val actual = assertFailsWith<ShortcutException> { adapter.sendAdvert(reach.sendsFlood) }
                        assertEquals(expected, actual.error)
                        assertSame(originalFailure, actual.cause)
                    }
                    assertEquals(listOf(if (reach.sendsFlood) Bytes.of(7, 1) else Bytes.of(7)),
                        firmware.sent.filter { it[0].toInt() == 7 })
                    assertTrue(store.fetchPendingSends(assertNotNull(h.appState.currentRadioId)).isEmpty())
                    for (recipient in recipients(h)) assertTrue(messages(store, recipient).isEmpty())
                    assertEquals(1, h.lifecycle.created.size)
                } finally { h.container.close() }
            }
        }
    }

    @Test fun rawAdvertSessionPayloadAndExistingShortcutFailureRetainIdentity() = runTest {
        val h = connected(this)
        try {
            val adapter = AppShortcutRadio(h.container)
            for (underlying in listOf(MeshCoreException.Timeout(), MeshCoreException.NotConnected())) {
                // The actual AdvertisementService wraps these; this separately covers the source's raw-error route.
                val mapped = adapter.advertFailure(underlying)
                assertSame(underlying, mapped.cause)
                assertSame(underlying, assertIs<ShortcutError.SessionError>(mapped.error).underlying)
                assertSame(mapped, adapter.advertFailure(mapped))
            }
            val existing = ShortcutException(ShortcutError.DENIED)
            assertSame(existing, adapter.advertFailure(existing))
            assertFalse(h.links.radios.single().sent.any { it[0].toInt() == 7 })
        } finally { h.container.close() }
    }

    @Test fun queuedResultRequiresOneRealDurableDirectAndChannelEnqueueWithoutWireReceipt() = runTest {
        val h = connected(this)
        try {
            for (recipient in recipients(h)) {
                assertEquals(SendResult.Queued, flow(h).execute(id(recipient), original))
                val message = messages(store, recipient).single()
                val pending = store.fetchPendingSendsForMessage(EntityKey(recipient.radioId, message.id)).single()
                assertEquals(original, message.text)
                assertFalse(pending.isResend)
                assertTrue(pending.sequence > 0)
                assertNotEquals(MessageStatus.DELIVERED, message.status)
                when (recipient) {
                    is ShortcutRecipient.Contact -> assertEquals(
                        DirectMessageEnvelope(message.id, recipient.dto.id), pending.directMessageEnvelope(),
                    )
                    is ShortcutRecipient.Channel -> assertEquals(
                        ChannelMessageEnvelope(message.id, recipient.dto.index, false, original, message.timestamp, "TestNode"),
                        pending.channelMessageEnvelope(),
                    )
                }
            }
            assertEquals(1, h.lifecycle.created.size)
            assertEquals(1, h.links.radios.size)
        } finally { h.container.close() }
    }

    @Test fun realEnqueueTransactionFailureNeverReturnsQueuedAndMarksActualRowsFailed() = runTest {
        val h = connected(this)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER wp502_reject_shortcut BEFORE INSERT ON pending_sends BEGIN SELECT RAISE(ABORT, 'shortcut fixture'); END",
        )
        try {
            for (recipient in recipients(h)) {
                val error = assertFailsWith<ShortcutException> { flow(h).execute(id(recipient), original) }
                assertEquals(ShortcutError.SEND_FAILED, error.error)
                val queue = assertIs<ChatSendQueueServiceException>(error.cause)
                assertIs<PersistenceStoreException>(assertIs<ChatSendQueueServiceError.PersistFailed>(queue.error).underlying)
                val message = messages(store, recipient).single()
                assertEquals(original, message.text)
                assertEquals(MessageStatus.FAILED, message.status)
                assertTrue(store.fetchPendingSendsForMessage(EntityKey(recipient.radioId, message.id)).isEmpty())
            }
            assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER wp502_reject_shortcut")
            h.container.close()
        }
    }

    @Test fun realPendingCreationFailureNeverReturnsQueuedOrPersistsOptimisticRows() = runTest {
        val h = connected(this)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER wp502_reject_shortcut_message BEFORE INSERT ON messages " +
                "BEGIN SELECT RAISE(ABORT, 'pending message fixture'); END",
        )
        try {
            for (recipient in recipients(h)) {
                val error = assertFailsWith<ShortcutException> { flow(h).execute(id(recipient), original) }
                assertEquals(ShortcutError.SEND_FAILED, error.error)
                assertIs<PersistenceStoreException>(error.cause)
                assertTrue(messages(store, recipient).isEmpty())
                assertTrue(store.fetchPendingSends(recipient.radioId).isEmpty())
            }
            assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER wp502_reject_shortcut_message")
            h.container.close()
        }
    }

    @Test fun rejectedFailureStatusWriteCannotReplaceTheTypedEnqueueError() = runTest {
        val h = connected(this)
        val records = Collections.synchronizedList(mutableListOf<LogRecord>())
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.message == "Mark shortcut pending send failed") records += record
            }
            override fun flush() = Unit
            override fun close() = Unit
        }
        val logger = Logger.getLogger("com.mc1.Shortcuts")
        logger.addHandler(handler)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER wp502_reject_shortcut_enqueue BEFORE INSERT ON pending_sends " +
                "BEGIN SELECT RAISE(ABORT, 'enqueue fixture'); END",
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER wp502_reject_shortcut_failure BEFORE UPDATE OF statusRawValue ON messages " +
                "BEGIN SELECT RAISE(ABORT, 'failed status fixture'); END",
        )
        try {
            for (recipient in recipients(h)) {
                val error = assertFailsWith<ShortcutException> { flow(h).execute(id(recipient), original) }
                assertEquals(ShortcutError.SEND_FAILED, error.error)
                val queue = assertIs<ChatSendQueueServiceException>(error.cause)
                assertIs<PersistenceStoreException>(assertIs<ChatSendQueueServiceError.PersistFailed>(queue.error).underlying)
                val message = messages(store, recipient).single()
                assertEquals(original, message.text)
                assertEquals(MessageStatus.PENDING, message.status)
                assertTrue(store.fetchPendingSendsForMessage(EntityKey(recipient.radioId, message.id)).isEmpty())
            }
            assertEquals(2, records.size)
            records.forEach { assertIs<PersistenceStoreException>(it.thrown) }
            assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER wp502_reject_shortcut_enqueue")
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER wp502_reject_shortcut_failure")
            logger.removeHandler(handler)
            h.container.close()
        }
    }

    @Test fun realAdapterRetainsTypedReadinessRecipientAndLengthErrorsWithoutCreatingRows() = runTest {
        val h = connected(this)
        try {
            val radio = AppShortcutRadio(h.container)
            val recipient = assertIs<ShortcutRecipient.Contact>(recipients(h).first())
            assertEquals(ShortcutError.MESSAGE_TOO_LONG, assertFailsWith<ShortcutException> {
                radio.queueMessage(recipient, "x".repeat(ProtocolLimits.MAX_DIRECT_MESSAGE_LENGTH + 1))
            }.error)
            assertEquals(ShortcutError.INVALID_RECIPIENT, assertFailsWith<ShortcutException> {
                radio.queueMessage(ShortcutRecipient.Contact(recipient.dto.copy(typeRawValue = ContactType.REPEATER.rawValue)), original)
            }.error)
            assertTrue(messages(store, recipient).isEmpty())
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertEquals(ShortcutError.NOT_CONNECTED, assertFailsWith<ShortcutException> {
                radio.queueMessage(recipient, original)
            }.error)
            assertTrue(messages(store, recipient).isEmpty())
        } finally { h.container.close() }
    }

    @Test fun appSessionFailurePayloadAndOuterCauseSurviveLocalizedShortcutPresentation() = runTest {
        val h = connected(this)
        val adapter = AppShortcutRadio(h.container)
        val base = ApplicationProvider.getApplicationContext<Context>()
        val detail = "Invalid \u6d4b\u8bd5 \u0645\u0631\u062d\u0628\u0627"
        try {
            val recipients = recipients(h)
            for (locale in listOf("en", "zh-Hans")) {
                val configuration = Configuration(base.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(locale)))
                }
                val context = base.createConfigurationContext(configuration)
                val localized = AndroidShortcutText(context)
                val timeout = MeshCoreException.Timeout()
                val failures = listOf(
                    timeout to context.getString(L10nR.string.l10n_app_localizable_error_meshcore_timeout),
                    MeshCoreException.NotConnected() to context.getString(L10nR.string.l10n_app_localizable_error_meshcore_notconnected),
                    MeshCoreException.BluetoothUnauthorized() to context.getString(L10nR.string.l10n_app_localizable_error_meshcore_bluetoothunauthorized),
                    MeshCoreException.InvalidInput(detail) to context.getString(L10nR.string.l10n_app_localizable_error_meshcore_invalidinput, detail),
                    MeshCoreException.ConnectionLost(timeout) to context.getString(
                        L10nR.string.l10n_app_localizable_error_meshcore_connectionlost,
                        context.getString(L10nR.string.l10n_app_localizable_error_meshcore_timeout),
                    ),
                    MeshCoreException.DeviceError(255u) to context.getString(L10nR.string.l10n_app_localizable_error_device_unknown, 255),
                )
                for ((underlying, expected) in failures) {
                    val wrappers = listOf(
                        MessageServiceException(MessageServiceError.SessionError(underlying)) to recipients.first(),
                        ChannelServiceError.SessionError(underlying) to recipients.last(),
                    )
                    for ((outer, recipient) in wrappers) {
                        val mapped = adapter.sendFailure(outer)
                        assertSame(outer, mapped.cause)
                        assertSame(underlying, assertIs<ShortcutError.SessionError>(mapped.error).underlying)
                        assertSame(mapped, adapter.sendFailure(mapped))
                        assertEquals(expected, localized.error(mapped.error))
                        assertNotEquals(localized.error(ShortcutError.SEND_FAILED), expected)
                        val shown = mutableListOf<String>()
                        // Synthetic fault injection checks presentation; real durable acceptance is tested separately.
                        val failingPort = object : ShortcutRadioPort by adapter {
                            override suspend fun queueMessage(recipient: ShortcutRecipient, text: String) {
                                throw mapped
                            }
                        }
                        val executor = ShortcutRequestExecutor(
                            failingPort,
                            object : ShortcutConfirmationPort {
                                override suspend fun confirm(request: ConfirmationRequest) = true
                            },
                            localized,
                            object : ShortcutPresenter {
                                override fun show(message: String) { shown += message }
                                override fun openApp(request: ShortcutRequest) {
                                    fail("A typed session error must not escalate or claim acceptance")
                                }
                            },
                        )
                        executor.run(ShortcutRequest.Send(id(recipient), original))
                        assertEquals(listOf(expected), shown)
                        assertTrue(messages(store, recipient).isEmpty())
                        assertTrue(store.fetchPendingSends(recipient.radioId).isEmpty())
                    }
                }
            }
            assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
        } finally { h.container.close() }
    }

    @Test fun confirmationConnectionDropDoesNotReportQueuedOrCreateAStaleRadioMessage() = runTest {
        val h = connected(this)
        try {
            val recipient = recipients(h).first()
            val result = flow(h) {
                h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                true
            }.execute(id(recipient), original)
            assertEquals(SendResult.NeedsForeground, result)
            assertTrue(messages(store, recipient).isEmpty())
            assertTrue(store.fetchPendingSends(recipient.radioId).isEmpty())
        } finally { h.container.close() }
    }

    @Test fun confirmationRadioReplacementNeverQueuesOrCreatesTheOldRecipient() = runTest {
        var identity = key(7)
        val h = connected(this, identity = { identity })
        try {
            val recipient = recipients(h).first()
            val result = flow(h) {
                h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                identity = key(8)
                h.manager.connect(h.target(UUID.fromString("50200000-0000-0000-0000-000000000317")))
                h.eventually("replacement radio ready") { h.manager.connectionState == DeviceConnectionState.READY }
                true
            }.execute(id(recipient), original)
            assertNotEquals(recipient.radioId, h.appState.currentRadioId)
            assertEquals(SendResult.NeedsForeground, result)
            assertTrue(messages(store, recipient).isEmpty())
            assertTrue(store.fetchPendingSends(recipient.radioId).isEmpty())
            assertTrue(store.fetchPendingSends(assertNotNull(h.appState.currentRadioId)).isEmpty())
            assertTrue(h.links.radios.all { firmware -> firmware.sent.none { it[0].toInt() in 2..3 } })
        } finally { h.container.close() }
    }

    @Test fun declinedOrCancelledConfirmationNeverPersistsOrPublishesDirectOrChannelSends() = runTest {
        val h = connected(this)
        val published = Collections.synchronizedList(mutableListOf<MessageEvent>())
        val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { event ->
                if (event is MessageEvent.DirectMessageReceived ||
                    event is MessageEvent.ChannelMessageReceived || event is MessageEvent.MessageFailed) {
                    published += event
                }
            }
        }
        try {
            for (recipient in recipients(h)) {
                for (cancel in listOf(false, true)) {
                    val requested = CompletableDeferred<ConfirmationRequest.SendMessage>()
                    val decision = CompletableDeferred<Boolean>()
                    val caller = backgroundScope.async {
                        flow(h) { request ->
                            requested.complete(assertIs<ConfirmationRequest.SendMessage>(request))
                            decision.await()
                        }.execute(id(recipient), original)
                    }
                    try {
                        h.eventually("actual shortcut awaits confirmation without accepting a send") { requested.isCompleted }
                        assertEquals(original, requested.await().message)
                        assertFalse(caller.isCompleted)
                        assertTrue(messages(store, recipient).isEmpty())
                        assertTrue(store.fetchPendingSends(recipient.radioId).isEmpty())
                        assertTrue(published.isEmpty())
                        assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
                        if (cancel) {
                            val cancellation = CancellationException("cancel unconfirmed shortcut")
                            caller.cancel(cancellation)
                            val actual = assertFailsWith<CancellationException> { caller.await() }
                            assertTrue(actual === cancellation || actual.cause === cancellation)
                            assertTrue(caller.isCancelled)
                        } else {
                            decision.complete(false)
                            assertEquals(SendResult.Declined, caller.await())
                        }
                        h.scheduler.runCurrent()
                        assertTrue(messages(store, recipient).isEmpty())
                        assertTrue(store.fetchPendingSends(recipient.radioId).isEmpty())
                        assertTrue(published.isEmpty())
                        assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
                    } finally { caller.cancelAndJoin() }
                }
            }
            assertEquals(1, h.lifecycle.created.size)
        } finally {
            observer.cancelAndJoin()
            h.container.close()
        }
    }

    @Test fun cancellationAfterPendingPublicationPropagatesButAcceptedQueueSurvivesGenerationReplacement() = runTest {
        cancelledAcceptedSend(this, channel = false)
    }

    @Test fun cancelledAcceptedChannelSendSurvivesGenerationReplacementWithoutDuplicateFailure() = runTest {
        cancelledAcceptedSend(this, channel = true)
    }

    private suspend fun cancelledAcceptedSend(test: TestScope, channel: Boolean) {
        val h = connected(test)
        val recipient = recipients(h).let { if (channel) it.last() else it.first() }
        val pendingID = CompletableDeferred<UUID>()
        val cancellation = CancellationException("cancel shortcut observer after pending creation")
        var caller: Job? = null
        var observed: CancellationException? = null
        var failures = 0
        val observer = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { event ->
                val message = when (event) {
                    is MessageEvent.DirectMessageReceived -> if (!channel) event.message else null
                    is MessageEvent.ChannelMessageReceived -> if (channel) event.message else null
                    else -> null
                }
                if (message?.text == original) {
                    pendingID.complete(message.id)
                    assertNotNull(caller).cancel(cancellation)
                }
                if (event is MessageEvent.MessageFailed) failures++
            }
        }
        try {
            caller = test.backgroundScope.launch {
                try { flow(h).execute(id(recipient), original) }
                catch (failure: CancellationException) { observed = failure; throw failure }
            }
            h.eventually("cancelled actual shortcut with durable queue row", virtualBudgetMillis = 0) {
                observed != null && pendingID.isCompleted && runBlocking {
                    store.fetchPendingSendsForMessage(EntityKey(recipient.radioId, pendingID.await())).size == 1
                }
            }
            val actual = assertNotNull(observed)
            // Coroutine stack-trace recovery may copy the exception, retaining the original as its cause.
            assertTrue(actual === cancellation || actual.cause === cancellation)
            assertEquals(cancellation.message, actual.message)
            assertTrue(assertNotNull(caller).isCancelled)
            val key = EntityKey(recipient.radioId, pendingID.await())
            val opcode = if (channel) 3 else 2
            h.eventually("accepted send starts one unacknowledged attempt before disconnect", virtualBudgetMillis = 0) {
                h.links.radios.single().sent.count { it[0].toInt() == opcode } == 1
            }
            assertEquals(1L, store.fetchPendingSendsForMessage(key).single().attemptCount)
            assertNotEquals(MessageStatus.FAILED, assertNotNull(store.fetchMessage(key)).status)
            assertEquals(0, failures)
            val old = assertNotNull(h.sessions.current)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertTrue(old.isTornDown)
            assertEquals(1, store.fetchPendingSendsForMessage(key).size)
            configure(h, rejectSend = true)
            h.manager.connect(h.target(target))
            h.eventually("replacement generation terminal status") {
                failures == 1 && h.sessions.current?.chatSendQueueService?.queuedEnvelopeCount == 0 && runBlocking {
                    store.fetchPendingSendsForMessage(key).isEmpty() &&
                        store.fetchMessage(key)?.status == MessageStatus.FAILED
                }
            }
            assertNotEquals(old.token, assertNotNull(h.sessions.current).token)
            assertEquals(2, h.lifecycle.created.size)
            assertEquals(original, assertNotNull(store.fetchMessage(key)).text)
            assertEquals(1, messages(store, recipient).size)
            // The first generation received no wire result; durable recovery legitimately retries once.
            assertEquals(listOf(1, 1), h.links.radios.map { firmware ->
                firmware.sent.count { it[0].toInt() == opcode }
            })
        } finally {
            caller?.cancelAndJoin()
            observer.cancelAndJoin()
            h.container.close()
        }
    }

    @Test fun syncingShortcutQueueWaitsForRealSyncCompletionWhileAdvertRemainsReadyOnly() = runTest {
        val radio = RadioId(UUID.fromString("447175B7-557D-4F48-B671-4B2CEE2DC932"))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Restored", isActive = false))
        val contact = ContactDTO(
            radioId = radio, publicKey = peerKey, name = "Synthetic shortcut peer",
            typeRawValue = ContactType.CHAT.rawValue, lastHeardTimestamp = null,
        )
        store.saveContact(contact)
        val h = ContainerHarness(this, store)
        configure(h, holdSync = true, rejectSend = true)
        val connect = backgroundScope.launch { h.manager.connect(h.target(target)) }
        try {
            h.eventually("actual SYNCING queue route") { h.manager.connectionState == DeviceConnectionState.SYNCING }
            h.eventually("registered held contact-list request") {
                h.links.radios.single().sent.any { it[0].toInt() == 4 }
            }
            assertEquals(SendResult.Queued, flow(h).execute(TargetIdentity.contactId(contact), original))
            assertEquals(DeviceConnectionState.SYNCING, h.manager.connectionState)
            assertEquals(1, store.fetchPendingSends(radio).size)
            assertFalse(h.links.radios.single().sent.any { it[0].toInt() in 2..3 })
            assertEquals(ShortcutError.NOT_CONNECTED, assertFailsWith<ShortcutException> {
                AppShortcutRadio(h.container).sendAdvert(false)
            }.error)
            val firmware = h.links.radios.single()
            firmware.push(Bytes.of(2) + le32(1))
            firmware.push(contactFrame(peerKey, "Synthetic shortcut peer"))
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("queued shortcut drains only after READY") {
                h.manager.connectionState == DeviceConnectionState.READY &&
                    firmware.sent.any { it[0].toInt() == 2 } && runBlocking { store.fetchPendingSends(radio).isEmpty() }
            }
            connect.join()
            assertEquals(1, firmware.sent.count { it[0].toInt() == 2 })
        } finally {
            connect.cancel()
            h.container.close()
        }
    }

    @Test fun acceptedDirectAndChannelQueueRowsSurviveActualFileStoreReopenAndDrainOnReconnect() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "wp502-shortcut-${UUID.randomUUID()}.db"
        val immediate = Executor { it.run() }
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        var database = open()
        var owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var backing = RoomPersistenceStore(database, owner)
        val h = connected(this, backing)
        var resumed: ContainerHarness? = null
        try {
            val keys = recipients(h, backing).map { recipient ->
                assertEquals(SendResult.Queued, flow(h).execute(id(recipient), original))
                EntityKey(recipient.radioId, messages(backing, recipient).single().id)
            }
            h.container.close()
            backing.close()
            owner.cancel()
            database.close()
            database = open()
            owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            backing = RoomPersistenceStore(database, owner)
            for (key in keys) {
                val message = assertNotNull(backing.fetchMessage(key))
                val accepted = backing.fetchPendingSendsForMessage(key).single()
                assertEquals(original, message.text)
                assertEquals(message.id, accepted.messageID)
                assertFalse(accepted.isResend)
            }
            assertEquals(2, backing.fetchPendingSends(keys.first().radioId).size)
            val replacement = connected(this, backing, rejectSend = true)
            resumed = replacement
            replacement.eventually("reopened shortcut queue drains through the replacement process graph") {
                replacement.sessions.current?.chatSendQueueService?.queuedEnvelopeCount == 0 && runBlocking {
                    backing.fetchPendingSends(keys.first().radioId).isEmpty() &&
                        keys.all { backing.fetchMessage(it)?.status == MessageStatus.FAILED }
                }
            }
            for (key in keys) {
                val message = assertNotNull(backing.fetchMessage(key))
                assertEquals(original, message.text)
                assertEquals(MessageStatus.FAILED, message.status)
                assertTrue(backing.fetchPendingSendsForMessage(key).isEmpty())
            }
            val firmware = replacement.links.radios.single()
            assertEquals(1, firmware.sent.count { it[0].toInt() == 2 })
            assertEquals(1, firmware.sent.count { it[0].toInt() == 3 })
            assertEquals(keys.first().radioId, replacement.appState.currentRadioId)
            assertEquals(1, replacement.lifecycle.created.size)
            assertEquals(0, assertNotNull(replacement.sessions.current).chatSendQueueService.queuedEnvelopeCount)
            assertEquals(1, backing.fetchMessages(keys.first().radioId, 0u, 100, 0).size)
            val restoredContact = assertNotNull(backing.fetchContact(keys.first().radioId, peerKey))
            assertEquals(1, backing.fetchMessages(EntityKey(keys.first().radioId, restoredContact.id), 100, 0).size)
        } finally {
            resumed?.container?.close()
            h.container.close()
            backing.close()
            owner.cancel()
            database.close()
            context.deleteDatabase(name)
        }
    }
}
