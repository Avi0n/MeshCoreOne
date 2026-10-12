// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceSendTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageService+SendDM.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 concrete unstarted session, typed transport failures and owned virtual clock.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingTransportFailureTest {
    @Test fun unstartedFinalizePreservesDeliveredRowAndReportsNotConnected() = runTest {
        val fixture = Fixture(this, MeshTransportError.NotConnected)
        try {
            val message = fixture.seed(MessageStatus.DELIVERED)
            val result = fixture.finalize(message)
            assertEquals(MessageStatus.DELIVERED, result.status)
            assertEquals(message.ackCode, result.ackCode)
            assertEquals(1, fixture.service.pendingAckCount)
            assertNull(fixture.session.currentSelfInfo)
            assertEquals(listOf(CommandCode.GET_CONTACT_BY_KEY.rawValue), fixture.transport.commands)
            fixture.assertRoutingFailure(message, MeshTransportError.NotConnected)
        } finally { fixture.close() }
    }

    @Test fun typedSendFailureInOptionalLookupDoesNotFailRetryExhaustion() = runTest {
        val cause = MeshTransportError.SendFailed("optional contact lookup")
        val fixture = Fixture(this, cause)
        try {
            val message = fixture.seed(MessageStatus.RETRYING)
            val result = fixture.finalize(message)
            assertEquals(MessageStatus.SENT, result.status)
            assertEquals(MessageStatus.SENT, fixture.store.fetchMessage(EntityKey(RADIO, message.id))?.status)
            assertEquals(1, fixture.service.pendingAckCount)
            fixture.assertRoutingFailure(message, cause)
        } finally { fixture.close() }
    }

    @Test fun routingCancellationPropagatesWithoutBeingReportedAsOptionalFailure() = runTest {
        val cancellation = CancellationException("routing caller cancelled")
        val fixture = Fixture(this, cancellation)
        try {
            val message = fixture.seed(MessageStatus.DELIVERED)
            assertSame(cancellation, assertFailsWith<CancellationException> { fixture.finalize(message) })
            assertEquals(MessageStatus.DELIVERED, fixture.store.fetchMessage(EntityKey(RADIO, message.id))?.status)
            assertTrue(fixture.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().isEmpty())
        } finally { fixture.close() }
    }

    @Test fun optionalRoutingFailureCannotInventAMissingMessage() = runTest {
        val fixture = Fixture(this, MeshTransportError.NotConnected)
        try {
            val message = fixture.seed(MessageStatus.DELIVERED)
            fixture.transport.beforeFailure = { fixture.store.messages.remove(EntityKey(RADIO, message.id)) }
            val failure = assertFailsWith<MessageServiceException> { fixture.finalize(message) }
            assertIs<MessageServiceError.SendFailed>(failure.error)
            assertNull(fixture.store.fetchMessage(EntityKey(RADIO, message.id)))
            fixture.assertRoutingFailure(message, MeshTransportError.NotConnected)
        } finally { fixture.close() }
    }

    @Test fun retiredGenerationIsNotConvertedIntoBestEffortSuccess() = runTest {
        val fixture = Fixture(this, MeshTransportError.NotConnected)
        try {
            val message = fixture.seed(MessageStatus.DELIVERED)
            fixture.signals.set(token(generation = 2), DeviceConnectionState.READY)
            val failure = assertFailsWith<MessageServiceException> { fixture.finalize(message) }
            assertEquals(MessageServiceError.NotConnected, failure.error)
            assertTrue(fixture.transport.commands.isEmpty())
            assertTrue(fixture.diagnostics.any { it is MessagingDiagnostic.StaleResult })
        } finally { fixture.close() }
    }

    @Test fun typedResetFailureIsReportedAndDoesNotRemoveTheFloodAttempt() = runTest {
        val fixture = Harness(this, MessageServiceConfig(maxAttempts = 3, floodAfter = 2, minTimeout = 0.0))
        val cause = MeshTransportError.SendFailed("path reset rejected")
        try {
            fixture.start()
            fixture.transport.beforeReply = {
                if (it[0] == CommandCode.RESET_PATH.rawValue) throw cause
            }
            val result = fixture.service.sendMessageWithRetry("optional path reset", fixture.contact)
            assertEquals(MessageStatus.SENT, result.status)
            assertEquals(3, fixture.sends(CommandCode.SEND_MESSAGE).size)
            val reset = fixture.transport.sentData.indexOfFirst { it[0] == CommandCode.RESET_PATH.rawValue }
            assertTrue(reset >= 0)
            assertEquals(2, fixture.transport.sentData.take(reset).count { it[0] == CommandCode.SEND_MESSAGE.rawValue })
            assertTrue(fixture.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "resetPath.${result.id}" && it.cause === cause
            })
        } finally { fixture.close() }
    }

    private class Fixture(test: TestScope, cause: Exception) {
        private val job = SupervisorJob(test.coroutineContext[Job])
        private val scope = CoroutineScope(test.coroutineContext + job)
        private val clock = TestMessagingClock(test.testScheduler)
        val store = RecordingStore()
        val signals = TestSignals()
        val diagnostics = mutableListOf<MessagingDiagnostic>()
        val transport = FailingTransport(cause)
        val session = MeshCoreSession(transport, SessionConfiguration(defaultTimeout = 5.0), clock, scope.coroutineContext)
        val service = MessageService(token(), SELF, session, store, signals, scope, clock = clock,
            reporter = MessagingIssueReporter { diagnostics += it })

        suspend fun seed(status: MessageStatus): MessageDTO {
            val ack = Bytes.of(0xFE, 0xED, 0xFA, 0xCE)
            val message = MessageDTO(radioId = RADIO, contactID = CONTACT, text = "Test message",
                timestamp = clock.wallClock.instant().epochSecond.toUInt(), createdAt = clock.wallClock.instant(),
                status = status, ackCode = ack.ackCodeUInt32)
            store.saveMessage(message)
            service.installPendingAck(PendingAck(message.id, CONTACT, SnapshotSet(listOf(ack)),
                clock.wallClock.instant(), 30.0))
            return message
        }

        suspend fun finalize(message: MessageDTO) =
            service.finalizeSend(message.id, CONTACT, TARGET, null, 0u)

        fun assertRoutingFailure(message: MessageDTO, cause: Throwable) {
            val diagnostic = diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().single()
            assertEquals("routing.${message.id}", diagnostic.operation)
            assertSame(cause, diagnostic.cause)
        }

        suspend fun close() {
            try { service.close() }
            finally {
                try { session.stop() }
                finally { job.cancelAndJoin() }
            }
        }
    }

    private class FailingTransport(private val cause: Exception) : MeshTransport {
        val commands = mutableListOf<UByte>()
        var beforeFailure: (() -> Unit)? = null
        override suspend fun connect(): Nothing = throw MeshTransportError.NotConnected
        override suspend fun disconnect() = Unit
        override suspend fun send(data: Bytes): Nothing {
            commands += data[0]
            beforeFailure?.invoke()
            throw cause
        }
        override suspend fun receivedData(): Flow<Bytes> = emptyFlow()
        override suspend fun isConnected() = false
    }
}
