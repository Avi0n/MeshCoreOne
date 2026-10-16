package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.ChannelMessage
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import kotlin.coroutines.coroutineContext

/**
 * Drains received messages off the device and feeds each through [MessageIngestPipeline]. Two sources:
 * the backlog drain ([pollAllMessages], one `getMessage` loop until no more) and the live event monitor
 * ([startEventMonitoring], the pushed message stream). The [isPolling] gate keeps the live monitor from
 * double-processing a message the drain is already handling. The Kotlin port of the Swift
 * `MessagePollingService`; contact/channel resolution and the CLI/signed textType routing live here so
 * the pipeline only ever sees a resolved conversation.
 *
 * The getMessage-coalescing lost-wakeup fix is bridge-provided, so [pollAllMessages] adds no app-level
 * locking around [IngestSession.getMessage]; concurrent poll requests coalesce safely below the bridge.
 */
class MessagePoller(
    private val radioID: Uuid,
    private val session: IngestSession,
    private val store: IngestStore,
    private val pipeline: MessageIngestPipeline,
    private val scope: CoroutineScope,
    private val edges: MessagePollerEdges,
) {
    private val isPolling = MutableStateFlow(false)
    private var eventMonitorJob: Job? = null

    /**
     * Drains every queued backlog message until [GetMessageResult.NoMoreMessages], returning the count of
     * user-visible messages processed. One block anchor is captured for the whole drain so the backlog
     * lands as a single contiguous block positioned at delivery time (§6.4). While draining, [isPolling]
     * suppresses the live monitor so a message is not processed twice. Datagrams are drained without
     * counting (not user-visible yet).
     */
    suspend fun pollAllMessages(): Int {
        isPolling.value = true
        val blockAnchor = edges.now()
        var count = 0
        try {
            while (true) {
                coroutineContext.ensureActive()
                when (val result = session.getMessage()) {
                    is GetMessageResult.ContactMessageReceived -> {
                        count += 1
                        ingestGuarded("contact") {
                            dispatchContactMessage(result.message, DeliveryContext.InitialSync(blockAnchor))
                        }
                    }

                    is GetMessageResult.ChannelMessageReceived -> {
                        count += 1
                        ingestGuarded("channel") {
                            dispatchChannelMessage(result.message, DeliveryContext.InitialSync(blockAnchor))
                        }
                    }

                    is GetMessageResult.ChannelDataReceived -> Unit
                    is GetMessageResult.NoMoreMessages -> return count
                }
            }
        } finally {
            isPolling.value = false
        }
    }

    /**
     * Subscribes to the pushed message stream and ingests each event as it arrives (context [Live]).
     * Events are skipped while [pollAllMessages] runs so the drain owns them. Idempotent: a prior monitor
     * is cancelled first.
     *
     * One faulting message must never end the subscription: `session.events` is a hot stream the radio keeps
     * draining, so a collector that dies takes every later inbound message with it, silently and until the
     * next reconnect. Each message is therefore ingested inside [ingestGuarded], which reports an ordinary
     * fault and moves on. Cancellation and an error still end the collector.
     */
    fun startEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = scope.launch {
            session.events.collect { event ->
                if (isPolling.value) return@collect
                when (event) {
                    is MeshEvent.ContactMessageReceived ->
                        ingestGuarded("contact") { dispatchContactMessage(event.message, DeliveryContext.Live) }

                    is MeshEvent.ChannelMessageReceived ->
                        ingestGuarded("channel") { dispatchChannelMessage(event.message, DeliveryContext.Live) }

                    else -> Unit
                }
            }
        }
    }

    /**
     * Runs one message's ingest. An ordinary fault is reported and the collector continues. Cancellation
     * and an error propagate, so a teardown or a broken runtime does not keep collecting.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend inline fun ingestGuarded(kind: String, block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            edges.logger?.error("Failed to save $kind message: $error")
            edges.onIngestFailure(error)
        }
    }

    /** Stops the live monitor so it releases the collaborators it captures. */
    fun stopEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = null
    }

    private suspend fun dispatchContactMessage(message: ContactMessage, context: DeliveryContext) {
        val contact = store.resolveContact(radioID, message.senderPublicKeyPrefix)
            ?: edges.materializePendingContact(message.senderPublicKeyPrefix)
        when (message.textType) {
            TextType.CLI_DATA.raw -> {
                // Live CLI is collected by NodeCliCoordinator from MeshEvent.ContactMessageReceived.
                // getMessage backlog never emits that event, so only InitialSync is handed to the
                // CLI handler (iOS SyncCoordinator.handleIncomingCLIMessage). Wiring live here would
                // double-fire admin observers.
                if (context !is DeliveryContext.Live) edges.cliMessageHandler(message, contact)
            }
            TextType.SIGNED_PLAIN.raw -> edges.signedMessageHandler(message, contact)
            else -> pipeline.ingest(IncomingMessage.Direct(message, contact), context)
        }
    }

    private suspend fun dispatchChannelMessage(message: ChannelMessage, context: DeliveryContext) {
        val channel = store.resolveChannel(radioID, message.channelIndex)
        pipeline.ingest(IncomingMessage.Channel(message, channel), context)
    }
}
