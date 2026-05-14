import SwiftUI
import MC1Services

/// ViewModel for room conversation operations
@Observable
@MainActor
final class RoomConversationViewModel {

    // MARK: - Properties

    /// Current room session
    var session: RemoteNodeSessionDTO?

    /// Room messages
    var messages: [RoomMessageDTO] = []

    /// Loading state
    var isLoading = false

    /// Whether data has been loaded at least once (prevents empty state flash)
    var hasLoadedOnce = false

    /// Error message if any
    var errorMessage: String?

    /// Message text being composed
    var composingText = ""

    /// Whether a message is being sent
    var isSending = false

    // MARK: - Dependencies

    private var roomServerService: RoomServerService?
    private var dataStore: DataStore?
    private var syncCoordinator: SyncCoordinator?
    private var notificationService: NotificationService?

    /// Pending coalesced reload spawned by `handleEvent`. Non-nil while a reload
    /// is scheduled but not yet fired, so a burst of room events triggers a
    /// single `loadMessages` instead of one per event.
    private var reloadTask: Task<Void, Never>?

    /// Debounce window before a coalesced reload fires. Long enough to batch
    /// the typical LoRa-paced room event burst, short enough that user-visible
    /// state still feels fresh.
    private static let reloadDebounce: Duration = .milliseconds(50)

    // MARK: - Initialization

    init() {}

    /// Configure with services from AppState
    func configure(appState: AppState) {
        self.roomServerService = appState.services?.roomServerService
        self.dataStore = appState.services?.dataStore
        self.syncCoordinator = appState.syncCoordinator
        self.notificationService = appState.services?.notificationService
    }

    // MARK: - Messages

    /// Load messages for the current session
    func loadMessages(for session: RemoteNodeSessionDTO) async {
        guard let roomServerService else { return }

        self.session = session
        isLoading = true
        errorMessage = nil

        do {
            messages = try await roomServerService.fetchMessages(sessionID: session.id)

            // Clear unread count and update badge
            try await roomServerService.markAsRead(sessionID: session.id)
            await notificationService?.updateBadgeCount()
            syncCoordinator?.notifyConversationsChanged()
        } catch {
            errorMessage = error.localizedDescription
        }

        hasLoadedOnce = true
        isLoading = false
    }

    /// Optimistically append a message if not already present.
    /// Called synchronously before async reload to ensure ChatTableView
    /// sees the new count immediately for unread tracking.
    func appendMessageIfNew(_ message: RoomMessageDTO) {
        guard !messages.contains(where: { $0.id == message.id }) else { return }
        messages.append(message)
    }

    /// Send a message to the current room
    func sendMessage(text: String) async {
        guard let session,
              let roomServerService,
              !text.isEmpty else {
            composingText = text
            return
        }

        isSending = true
        errorMessage = nil

        do {
            let message = try await roomServerService.postMessage(sessionID: session.id, text: text)

            // Add to local array
            messages.append(message)
        } catch {
            errorMessage = error.localizedDescription
        }

        isSending = false
    }

    /// Refresh messages for current session
    func refreshMessages() async {
        guard let session else { return }
        await loadMessages(for: session)
    }

    /// Refresh session state from database
    func refreshSession() async {
        guard let session, let dataStore else { return }

        if let updated = try? await dataStore.fetchRemoteNodeSession(id: session.id) {
            self.session = updated
        }
    }

    /// Fold a `MessageEvent` from `MessageEventStream` into view-model state.
    /// Called on the main actor from a SwiftUI `.task` consumer in
    /// `RoomConversationView`. The exhaustive switch is deliberate — a new
    /// `MessageEvent` case becomes a compile error rather than a silent skip.
    func handleEvent(_ event: MessageEvent) async {
        guard let session else { return }

        switch event {
        case .roomMessageReceived(let message, let sessionID):
            // Optimistic append first so the ChatTableView sees the new count
            // immediately for unread tracking, then coalesce the reload so a
            // burst of incoming room messages triggers one DB sync, not N.
            guard sessionID == session.id else { return }
            appendMessageIfNew(message)
            scheduleCoalescedReload()

        case .roomMessageStatusUpdated(let messageID):
            if messages.contains(where: { $0.id == messageID }) {
                scheduleCoalescedReload()
            }

        case .roomMessageFailed(let messageID):
            if messages.contains(where: { $0.id == messageID }) {
                scheduleCoalescedReload()
            }

        case .directMessageReceived, .channelMessageReceived,
             .messageStatusResolved, .messageResent, .messageFailed, .messageRetrying,
             .heardRepeatRecorded, .reactionReceived, .routingChanged:
            // Non-Room events are not Room-scoped. Enumerated explicitly so
            // adding a new MessageEvent case surfaces as a non-exhaustive
            // switch compile error rather than a silent skip.
            break
        }
    }

    /// Schedules a debounced reload so bursts of room events trigger one
    /// `loadMessages` instead of one per event. No-ops if a reload is
    /// already pending.
    private func scheduleCoalescedReload() {
        guard reloadTask == nil else { return }
        reloadTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: Self.reloadDebounce)
            guard let self else { return }
            self.reloadTask = nil
            guard let session = self.session else { return }
            await self.loadMessages(for: session)
        }
    }

    /// Retry sending a failed room message
    func retryMessage(id: UUID) async {
        guard let roomServerService else { return }

        do {
            let updatedMessage = try await roomServerService.retryMessage(id: id)
            // Update local array
            if let index = messages.firstIndex(where: { $0.id == id }) {
                messages[index] = updatedMessage
            }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    // MARK: - Timestamp Helpers

    /// Time gap (in seconds) that breaks message grouping for timestamps.
    static let messageGroupingGapSeconds = 300

    /// Determines if a timestamp should be shown for a message at the given index.
    /// Shows timestamp for first message or when there's a gap > 5 minutes.
    static func shouldShowTimestamp(at index: Int, in messages: [RoomMessageDTO]) -> Bool {
        guard index > 0 else { return true }

        let currentMessage = messages[index]
        let previousMessage = messages[index - 1]

        let gap = abs(Int(currentMessage.timestamp) - Int(previousMessage.timestamp))
        return gap > messageGroupingGapSeconds
    }
}
