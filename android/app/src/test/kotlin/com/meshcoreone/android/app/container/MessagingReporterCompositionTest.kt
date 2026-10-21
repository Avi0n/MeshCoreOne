// AndroidOnly: WP-501 Actual App factory reporter injection and redacted default logging, not another queue original.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.messaging.LoggingMessagingIssueReporter
import com.meshcoreone.android.core.services.rendering.DraftStore
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagingReporterCompositionTest : RoomProcessTest() {
    @Test fun defaultAppFactoryLogsItsQueueDiagnosticExactlyOnce() = runTest {
        val links = HarnessLinks { key(7) }
        replyWithUnconfiguredChannels(links)
        val dependencies = defaultDependencies(this, links)
        assertSame(LoggingMessagingIssueReporter, dependencies.messagingReporter)
        captureMessagingLogs { logs ->
            withOwnedApp(AppContainer(dependencies)) { app ->
                val token = rejectAfterQueueShutdown(app, links)
                assertRedacted(logs, "generation=${token.generation.value} operation=persistPendingSend")
            }
            assertEquals(1, logs.size, "Process close must not replay or double-report the diagnostic")
        }
    }

    @Test fun injectedAppFactoryReportsItsQueueDiagnosticOnceWithoutDefaultLogging() = runTest {
        val h = ContainerHarness(this, store)
        replyWithUnconfiguredChannels(h.links)
        captureMessagingLogs { logs ->
            var observed: List<MessagingDiagnostic> = emptyList()
            withOwnedApp(h.container) { app ->
                val token = rejectAfterQueueShutdown(app, h.links)
                val diagnostics = synchronized(h.messagingDiagnostics) { h.messagingDiagnostics.toList() }
                assertEquals(listOf(MessagingDiagnostic.StaleResult(token, "persistPendingSend")), diagnostics)
                observed = diagnostics
                assertTrue(logs.isEmpty(), "An explicit reporter replaces the default; it must not also log")
            }
            assertEquals(observed, synchronized(h.messagingDiagnostics) { h.messagingDiagnostics.toList() })
            assertTrue(logs.isEmpty(), "Process close must not duplicate the injected observation")
        }
    }

    @Test fun standaloneSessionDefaultLogsOneRedactedFailureWithoutSensitiveCauseData() = runTest {
        val dependencies = defaultDependencies(this, HarnessLinks { key(7) })
        val environment = SessionEnvironment(
            store, backgroundScope, dependencies.notificationDelivery, dependencies.notificationPreferences,
            null, dependencies.foreground, dependencies.passwords, dependencies.contactPreferences,
            object : RuntimeSyncState {
                override val detectedPlatform = DevicePlatform.UNKNOWN
                override val lastCleanChannelSync: Pair<RadioId, Instant>? = null
                override val lastAttemptedChannelSync: Pair<RadioId, Instant>? = null
                override fun requestDisconnect(reason: RuntimeDisconnectReason) = error("No runtime in this default-constructor test")
            },
            ResyncFailureSignal { error("No resync in this default-constructor test") },
        )
        assertSame(LoggingMessagingIssueReporter, environment.messagingReporter)
        assertSame(dependencies.messagingReporter, environment.messagingReporter)
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("C0000000-0000-0000-0000-000000000001")), Generation(7),
            RadioId(UUID.fromString("C0000000-0000-0000-0000-000000000002")),
        )
        val cause = IllegalStateException("private message / node / secret")
        val diagnostic = MessagingDiagnostic.Failure(token, "channelQuery.example", cause)
        captureMessagingLogs { logs ->
            environment.messagingReporter.report(diagnostic)
            assertRedacted(logs, "generation=7 operation=channelQuery.example:IllegalStateException")
            assertFalse(logs.single().message.contains(token.epoch.value.toString()))
            assertFalse(logs.single().message.contains(token.radioId.value.toString()))
            assertFalse(logs.single().message.contains(requireNotNull(cause.message)))
        }
    }

    private fun defaultDependencies(test: TestScope, links: HarnessLinks) = AppContainerDependencies(
        store = store,
        passwords = MemoryVault(),
        connectionPreferences = MemoryConnectionPreferences(),
        connectivity = newConnectivity(),
        linkProbe = NoSystemLinks,
        scans = newScans(),
        linkFactory = links,
        notificationDelivery = RecordingDelivery(),
        notificationPreferences = FixedNotificationPreferences(),
        contactPreferences = MemoryFlags(),
        draftStore = DraftStore(MemoryDraftDefaults()),
        mainScope = test.backgroundScope,
        runtimeClock = SchedulerClock { test.testScheduler.currentTime },
        runtimeContext = test.backgroundScope.coroutineContext,
        accessibility = SilentAnnouncements,
    )

    private fun replyWithUnconfiguredChannels(links: HarnessLinks) {
        links.configureRadio = { radio ->
            radio.respond = { command ->
                if (command[0] == 31.toUByte()) {
                    listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                } else null
            }
        }
    }

    private suspend fun rejectAfterQueueShutdown(app: AppContainer, links: HarnessLinks): SessionToken {
        val targetID = UUID.fromString("C0000000-0000-0000-0000-000000000003")
        app.connectionManager.connect(ConnectionTarget.Bluetooth(
            BluetoothPairingHandle(BluetoothAddress(syntheticAddress(targetID)), null), targetID,
        ))
        assertEquals(DeviceConnectionState.READY, app.connectionManager.connectionState)
        val live = assertNotNull(app.sessions.current)
        assertEquals(live.token, app.connectionManager.snapshot.value.token)
        assertEquals(1, app.sessions.created)
        assertEquals(1, links.radios.single().connects)
        val message = MessageDTO(
            id = UUID.fromString("C0000000-0000-0000-0000-000000000004"), radioId = live.token.radioId,
            channelIndex = 0u, text = "private queued content", timestamp = 1_704_067_200u,
            createdAt = EPOCH, sortDate = EPOCH, direction = MessageDirection.OUTGOING, status = MessageStatus.PENDING,
        )
        store.saveMessage(message)
        val events = live.messageService.statusEvents()
        val wire = links.radios.single().sent.toList()
        val receipt = live.chatSendQueueService.shutdown()
        assertTrue(receipt.isComplete)
        val failure = assertFailsWith<ChatSendQueueServiceException> {
            live.chatSendQueueService.enqueueChannel(ChannelMessageEnvelope(
                message.id, 0u, false, message.text, message.timestamp, "private node",
            ))
        }
        assertEquals(ChatSendQueueServiceError.NotConnected, failure.error)
        assertSame(receipt, live.chatSendQueueService.shutdown())
        assertFalse(live.chatSendQueueService.isObservingConnection)
        assertEquals(0, live.chatSendQueueService.queuedEnvelopeCount)
        assertEquals(message, store.fetchMessage(EntityKey(live.token.radioId, message.id)))
        assertTrue(store.fetchPendingSends(live.token.radioId).isEmpty())
        assertEquals(wire, links.radios.single().sent, "Rejected enqueue must not transmit or query")
        assertSame(live, app.sessions.current)
        assertEquals(live.token, app.connectionManager.snapshot.value.token)
        val report = app.connectionManager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
        assertTrue(report.isComplete, "Unexpected teardown issue: ${report.issues}")
        assertTrue(live.isTornDown)
        assertEquals(0, live.liveJobCount)
        assertEquals(0, app.sessions.outstanding)
        assertNull(app.sessions.current)
        assertEquals(0, links.radios.single().readers)
        assertEquals(1, links.radios.single().closes)
        assertEquals(message, store.fetchMessage(EntityKey(live.token.radioId, message.id)))
        assertTrue(store.fetchPendingSends(live.token.radioId).isEmpty())
        assertTrue(events.events.toList().isEmpty())
        return live.token
    }

    private fun assertRedacted(logs: List<LogRecord>, message: String) {
        val record = logs.single()
        assertEquals("MeshCore.Messaging", record.loggerName)
        assertEquals(Level.WARNING, record.level)
        assertEquals(message, record.message)
        assertNull(record.thrown, "Cause payload must not leak through the platform logger")
        assertTrue(record.parameters.isNullOrEmpty())
    }

    private suspend fun captureMessagingLogs(body: suspend (List<LogRecord>) -> Unit) {
        val logger = Logger.getLogger("MeshCore.Messaging")
        val logs = Collections.synchronizedList(mutableListOf<LogRecord>())
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { logs += record }
            override fun flush() = Unit
            override fun close() = Unit
        }
        logger.addHandler(handler)
        try { body(logs) } finally { logger.removeHandler(handler) }
    }

    private suspend fun withOwnedApp(app: AppContainer, body: suspend (AppContainer) -> Unit) {
        var primary: Throwable? = null
        try { body(app) }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { withContext(NonCancellable) { app.close() } }
            catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }
}
