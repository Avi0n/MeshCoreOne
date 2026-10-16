package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/**
 * The room conversation logic core, mirroring the Swift `RoomConversationViewModel` minus the SwiftUI shell:
 * message-list assembly over [RoomServerService] reads, optimistic send/retry delegating to
 * `postMessage`/`retryMessage`, room-server event folding (status update → coalesced reload, connection
 * recovery → session refresh), unread reset on view, and the message-grouping timestamp rule. Compose- and
 * `L10n`-free; [errorMessage] is mapped [RoomServerError] copy, else the load/send fallback.
 *
 * State is confined to a single (main) dispatcher the way the Swift `@MainActor` VM is; the coalesced-reload
 * [reloadJob] runs on [RoomConversationInputs.scope]. A nil [RoomConversationInputs.roomServerService]
 * mirrors a disconnected state, so operations no-op — matching the Swift provider closures.
 */
class RoomConversationController(internal val inputs: RoomConversationInputs) {
    var session: RemoteNodeSessionDto? = null
        private set
    var messages: List<RoomMessageDto> = emptyList()
        private set
    var isLoading: Boolean = false
        private set
    var hasLoadedOnce: Boolean = false
        private set
    var isSending: Boolean = false
        private set
    var errorMessage: String? = null
    var composingText: String = ""

    private var reloadJob: Job? = null
    private var isClosed = false
    private var sessionRevision = 0L

    // MARK: Messages

    /**
     * Loads the session's messages, resets unread, and fires the on-viewed side effects (notification-tray
     * clear, badge, sync coordinator) through [RoomConversationInputs.onViewed]. Sets [hasLoadedOnce] so
     * the empty state no longer flashes a spinner. A nil service no-ops.
     */
    suspend fun loadMessages(session: RemoteNodeSessionDto) {
        // A queued initial load must keep a younger row already adopted by the mounted route.
        val keepAdoptedSnapshot = sessionRevision != 0L && this.session?.hasSameRoomIdentity(session) == true
        if (!keepAdoptedSnapshot) this.session = session
        val service = inputs.roomServerService()
        if (service == null) {
            // Disconnected with no offline store: show empty state, not a stuck spinner.
            messages = emptyList()
            hasLoadedOnce = true
            isLoading = false
            return
        }
        isLoading = true
        errorMessage = null
        // A failed load reports the mapped text, or the load fallback. Cancellation leaves the spinner up.
        runRoomConversation(
            onFailure = { error -> errorMessage = inputs.errorText(error) ?: inputs.loadFailedMessage() },
        ) {
            messages = service.fetchMessages(session.id)
            service.markAsRead(session.id)
            service.markFailedSendsSeen(session.id)
            inputs.onViewed?.invoke(session.id)
        }
        hasLoadedOnce = true
        isLoading = false
    }

    /**
     * Inserts a message in server-timestamp order, deduped by id, so the view and [shouldShowTimestamp]
     * stay chronological before the debounced reload re-sorts. Live arrivals can be older than the tail
     * (routine on LoRa and during history sync); ties keep arrival order to match the store's
     * `[timestamp, createdAt]` sort.
     */
    fun appendMessageIfNew(message: RoomMessageDto) {
        if (messages.any { it.id == message.id }) return
        val insertion = messages.indexOfFirst { it.timestamp > message.timestamp }
        val index = if (insertion < 0) messages.size else insertion
        messages = messages.toMutableList().apply { add(index, message) }
    }

    /**
     * Posts [text] to the current room and appends the optimistic pending message. Empty text or a nil
     * session/service stashes the text back into [composingText] and no-ops.
     */
    suspend fun sendMessage(text: String) {
        val current = session
        val service = inputs.roomServerService()
        if (current == null || service == null || text.isEmpty()) {
            composingText = text
            return
        }
        if (text.encodeToByteArray().size > ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES) {
            composingText = text
            return
        }
        isSending = true
        errorMessage = null
        // A failed send reports the mapped text, or the send fallback. Cancellation leaves the spinner up.
        runRoomConversation(
            onFailure = { error -> errorMessage = inputs.errorText(error) ?: inputs.sendFailedMessage() },
        ) {
            appendMessageIfNew(service.postMessage(current.id, text))
        }
        isSending = false
    }

    /** Retries a failed message and replaces it in place with the resolved row. */
    suspend fun retryMessage(id: Uuid) {
        val service = inputs.roomServerService() ?: return
        // A failed retry reports the mapped text, or the send fallback. Cancellation leaves the row in place.
        runRoomConversation(
            onFailure = { error -> errorMessage = inputs.errorText(error) ?: inputs.sendFailedMessage() },
        ) {
            val updated = service.retryMessage(id)
            messages = messages.map { if (it.id == id) updated else it }
        }
    }

    /** Re-reads the session row (e.g. after a reconnection) so connection/permission state stays fresh. */
    suspend fun refreshSession() {
        val current = session ?: return
        val fetch = inputs.fetchSession ?: return
        val revision = sessionRevision
        val refreshed = readRoomSessionForRefresh(
            read = { fetch(current.id) },
            logWarning = inputs.logWarning ?: { line ->
                PersistentLogger(inputs.scope, category = ROOM_CONVERSATION_LOG).warning(line)
            },
        )
        currentCoroutineContext().ensureActive()
        val stillCurrent = revision == sessionRevision && session === current
        if (!isClosed && stillCurrent && refreshed?.hasSameRoomIdentity(current) == true) {
            session = refreshed
        }
    }

    /** Adopts a persisted row without restarting the conversation's messages or draft. */
    fun adoptSessionSnapshot(snapshot: RemoteNodeSessionDto): Boolean {
        if (isClosed || session?.hasSameRoomIdentity(snapshot) == false) return false
        sessionRevision += 1
        session = snapshot
        return true
    }

    // MARK: Events

    /**
     * Folds a [RoomServerEvent] into state. A status update for a message we hold triggers a coalesced
     * reload; a connection recovery for our session refreshes it. The `when` is exhaustive (no `else`) so a
     * new event case is a compile error, mirroring the Swift switch.
     */
    suspend fun handleServerEvent(event: RoomServerEvent) {
        when (event) {
            is RoomServerEvent.StatusUpdated -> handleStatusUpdated(event)

            is RoomServerEvent.ConnectionRecovered ->
                if (event.sessionID == session?.id) refreshSession()

            is RoomServerEvent.MessageReceived -> onMessageReceived(event.message, event.sessionID)
        }
    }

    /**
     * Ingest entry for an incoming room message (the Swift `roomMessageReceived` case): optimistic append
     * so the list sees the new count immediately, then a coalesced reload.
     */
    fun onMessageReceived(message: RoomMessageDto, sessionID: Uuid) {
        if (sessionID != session?.id) return
        appendMessageIfNew(message)
        scheduleCoalescedReload()
    }

    /** Debounces reloads so a burst of room events triggers one [loadMessages], not one per event. */
    internal fun scheduleCoalescedReload() {
        if (isClosed || reloadJob?.isActive == true) return
        reloadJob = inputs.scope.launch {
            delay(RELOAD_DEBOUNCE_MS)
            session?.let { loadMessages(it) }
        }
    }

    /** Cancels delayed work owned by this conversation instance. */
    fun close() {
        if (isClosed) return
        isClosed = true
        reloadJob?.cancel()
        reloadJob = null
    }

    companion object {
        /** Debounce window before a coalesced reload fires, matching the Swift `reloadDebounce`. */
        const val RELOAD_DEBOUNCE_MS: Long = 50

        /** Timestamp-grouping gap: a timestamp shows when the gap to the previous message exceeds this. */
        const val MESSAGE_GROUPING_GAP_SECONDS: Int = 300

        /** Whether a timestamp should show at [index]: always for the first, else when the gap exceeds 300 s. */
        fun shouldShowTimestamp(index: Int, messages: List<RoomMessageDto>): Boolean {
            if (index <= 0) return true
            val gap = kotlin.math.abs(messages[index].timestamp - messages[index - 1].timestamp)
            return gap > MESSAGE_GROUPING_GAP_SECONDS
        }

        /**
         * Incoming rooms cluster on [RoomMessageDto.authorKeyPrefix] plus [MESSAGE_GROUPING_GAP_SECONDS].
         * Display-name matches do not merge prefixes. Mirrors iOS `incomingClusterContinues`.
         */
        fun incomingClusterContinues(earlier: RoomMessageDto, later: RoomMessageDto): Boolean {
            if (earlier.isFromSelf || later.isFromSelf) return false
            val gap = kotlin.math.abs(later.timestamp - earlier.timestamp)
            if (gap > MESSAGE_GROUPING_GAP_SECONDS) return false
            return earlier.authorKeyPrefix.contentEquals(later.authorKeyPrefix)
        }

        /**
         * Name on cluster-start, avatar on cluster-end. Self messages are excluded.
         * Mirrors iOS `incomingBookends`.
         */
        fun incomingBookends(messages: List<RoomMessageDto>): RoomIncomingBookends {
            val nameIDs = mutableSetOf<Uuid>()
            val avatarIDs = mutableSetOf<Uuid>()
            messages.forEachIndexed { index, message ->
                if (message.isFromSelf) return@forEachIndexed
                val previous = messages.getOrNull(index - 1)
                val next = messages.getOrNull(index + 1)
                val continuesFromPrevious = previous?.let { incomingClusterContinues(it, message) } == true
                val continuesToNext = next?.let { incomingClusterContinues(message, it) } == true
                if (!continuesFromPrevious) nameIDs.add(message.id)
                if (!continuesToNext) avatarIDs.add(message.id)
            }
            return RoomIncomingBookends(nameIDs = nameIDs, avatarIDs = avatarIDs)
        }
    }
}

/** Incoming room cluster bookends: name on start, avatar on end. */
data class RoomIncomingBookends(val nameIDs: Set<Uuid>, val avatarIDs: Set<Uuid>)

data class RoomConversationInputs(
    val roomServerService: () -> RoomServerService?,
    val scope: CoroutineScope,
    val fetchSession: (suspend (Uuid) -> RemoteNodeSessionDto?)? = null,
    val onViewed: (suspend (Uuid) -> Unit)? = null,
    /**
     * Localized text for a room-server failure, resolved at composition because this controller cannot
     * read string resources. Returns null when nothing shipped describes the failure; the load/send
     * fallback is then used instead of interpolating the exception.
     */
    val errorText: (Throwable) -> String? = { null },
    /** Current localized fallback when a load fault is not a mapped [RoomServerError]. */
    val loadFailedMessage: () -> String,
    /** Current localized fallback when a send or retry fault is not a mapped [RoomServerError]. */
    val sendFailedMessage: () -> String,
    /**
     * Warning sink for a session refresh that fails. Null writes the room conversation debug log.
     */
    val logWarning: ((String) -> Unit)? = null,
)

/** Debug-log category for the open room conversation. */
internal const val ROOM_CONVERSATION_LOG = "RoomConversation"

/** Dump when a room session refresh cannot read the stored row. */
internal const val ROOM_SESSION_REFRESH_FAILED = "Room session refresh failed"

/**
 * Re-reads one room session. A store fault is logged and returned as null so the open session stays.
 * Cancellation and an Error still propagate.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun readRoomSessionForRefresh(
    read: suspend () -> RemoteNodeSessionDto?,
    logWarning: (String) -> Unit,
): RemoteNodeSessionDto? {
    try {
        return read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        logWarning("$ROOM_SESSION_REFRESH_FAILED: ${error.message ?: error}")
        return null
    }
}
