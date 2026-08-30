import MC1Services
import OSLog
import SwiftUI

/// ViewModel for room conversation operations
@Observable
@MainActor
final class RoomConversationViewModel {
  private let logger = Logger(subsystem: "com.mc1", category: "RoomConversationViewModel")

  // MARK: - Properties

  /// Current room session
  var session: RemoteNodeSessionDTO?

  /// Room messages
  var messages: [RoomMessageDTO] = []

  /// Materialized tiled rows. Chrome including Translation offer lives here
  /// so `ChatTiledView` reconfigures when a phase changes.
  var tiledRows: [RoomTiledRow] = []

  /// Pending Translation session request. Observed so the view can invalidate
  /// `TranslationSession.Configuration`.
  var translationSessionRequest: TranslationSessionRequest?

  var preferredLanguageCode: String = EnvInputs.defaultPreferredLanguageCode

  /// Monotonic generation for in-flight translation. Apply a result only
  /// when it still matches `translationSessionRequest.generation`.
  @ObservationIgnored var translationGeneration: UInt64 = 0

  /// Detected language keyed by message id. Missing key = not yet run;
  /// `.undetermined` = ran, no code.
  var detectedLanguages: [UUID: DetectedLanguage] = [:]
  /// Per-message Translation offer phase. Missing lets
  /// `MessageTranslationChrome.resolved` decide from detection.
  var translationPhases: [UUID: MessageTranslationChrome.Phase] = [:]
  /// Last successful translation per message, keyed by collapsed target
  /// language code. A DE→EN result must not be reused as DE→FR.
  var translationCache: [UUID: [String: String]] = [:]

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

  private var roomServerServiceProvider: @MainActor () -> RoomServerService? = { nil }
  var roomServerService: RoomServerService? {
    roomServerServiceProvider()
  }

  private var dataStoreProvider: @MainActor () -> DataStore? = { nil }
  var dataStore: DataStore? {
    dataStoreProvider()
  }

  private var syncCoordinatorProvider: @MainActor () -> SyncCoordinator? = { nil }
  var syncCoordinator: SyncCoordinator? {
    syncCoordinatorProvider()
  }

  private var notificationServiceProvider: @MainActor () -> NotificationService? = { nil }
  var notificationService: NotificationService? {
    notificationServiceProvider()
  }

  /// Pending coalesced reload spawned by `handleEvent`. Non-nil while a reload
  /// is scheduled but not yet fired, so a burst of room events triggers a
  /// single `loadMessages` instead of one per event.
  private var reloadTask: Task<Void, Never>?

  /// Debounce window before a coalesced reload fires. Long enough to batch
  /// the typical LoRa-paced room event burst, short enough that user-visible
  /// state still feels fresh.
  private static let reloadDebounce: Duration = .milliseconds(50)

  /// View-owned drop animation. Weak so a disappeared room cannot start a
  /// flight after its overlay is gone.
  @ObservationIgnored weak var incomingAvatarFlight: IncomingAvatarFlight?

  // MARK: - Initialization

  init() {}

  /// Nil services mirror a disconnected state; operations then no-op.
  func configure(
    roomServerService: @escaping @MainActor () -> RoomServerService?,
    dataStore: @escaping @MainActor () -> DataStore?,
    syncCoordinator: @escaping @MainActor () -> SyncCoordinator?,
    notificationService: @escaping @MainActor () -> NotificationService?
  ) {
    roomServerServiceProvider = roomServerService
    dataStoreProvider = dataStore
    syncCoordinatorProvider = syncCoordinator
    notificationServiceProvider = notificationService
  }

  // MARK: - Messages

  /// Load messages for the current session
  func loadMessages(for session: RemoteNodeSessionDTO) async {
    guard let roomServerService else { return }
    let notificationService = notificationService
    let syncCoordinator = syncCoordinator

    self.session = session
    isLoading = true
    errorMessage = nil

    do {
      messages = try await roomServerService.fetchMessages(sessionID: session.id)
      refreshTiledRows()

      // Clear unread count, remove any delivered notifications for this
      // room still in the tray, and update the badge
      try await roomServerService.markAsRead(sessionID: session.id)
      try await dataStore?.markFailedSendsSeen(roomSessionID: session.id)
      await notificationService?.removeDeliveredNotifications(forRoomSessionID: session.id)
      await notificationService?.updateBadgeCount()
      syncCoordinator?.notifyConversationsChanged()
    } catch {
      errorMessage = error.userFacingMessage
    }

    hasLoadedOnce = true
    isLoading = false
  }

  /// Insert in server-timestamp order, deduped by id, so the view and
  /// `shouldShowTimestamp` stay chronological before the debounced reload
  /// re-sorts. Live arrivals can be older than the tail (routine on LoRa and
  /// during history sync); ties keep arrival order to match the store's
  /// `[timestamp, createdAt]` sort.
  func appendMessageIfNew(_ message: RoomMessageDTO) {
    guard !messages.contains(where: { $0.id == message.id }) else { return }
    let previousTail = messages.last
    let index = messages.firstIndex { $0.timestamp > message.timestamp } ?? messages.endIndex
    let isTailAppend = index == messages.endIndex
    messages.insert(message, at: index)
    refreshTiledRows()
    if isTailAppend,
       let previous = previousTail,
       Self.incomingClusterContinues(from: previous, to: message) {
      incomingAvatarFlight?.beginFlight(
        from: previous.id,
        to: message.id,
        identity: .initials(name: message.authorDisplayName)
      )
    }
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
      appendMessageIfNew(message)
    } catch {
      errorMessage = error.userFacingMessage
    }

    isSending = false
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
    case let .roomMessageReceived(message, sessionID):
      // Optimistic append first so the message list sees the new count
      // immediately for unread tracking, then coalesce the reload so a
      // burst of incoming room messages triggers one DB sync, not N.
      guard sessionID == session.id else { return }
      appendMessageIfNew(message)
      scheduleCoalescedReload()

    case let .roomMessageStatusUpdated(messageID):
      if messages.contains(where: { $0.id == messageID }) {
        scheduleCoalescedReload()
      }

    case let .roomMessageFailed(messageID):
      if messages.contains(where: { $0.id == messageID }) {
        scheduleCoalescedReload()
        do {
          try await dataStore?.markFailedSendsSeen(roomSessionID: session.id)
          syncCoordinator?.notifyConversationsChanged()
        } catch {
          logger.warning("Failed to mark in-thread room failed send seen: \(error.localizedDescription)")
        }
      }

    case .directMessageReceived, .channelMessageReceived,
         .messageStatusResolved, .messageResent, .messageFailed, .messageRetrying,
         .heardRepeatRecorded, .reactionReceived, .messagesRegionUpdated, .routingChanged:
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
      reloadTask = nil
      guard let session else { return }
      await loadMessages(for: session)
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
        refreshTiledRows()
      }
    } catch {
      errorMessage = error.userFacingMessage
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

  /// Incoming rooms cluster on `authorKeyPrefix` plus `messageGroupingGapSeconds`.
  /// Display-name matches do not merge prefixes.
  static func incomingClusterContinues(from earlier: RoomMessageDTO, to later: RoomMessageDTO) -> Bool {
    guard !earlier.isFromSelf, !later.isFromSelf else { return false }
    let gap = abs(Int(later.timestamp) - Int(earlier.timestamp))
    guard gap <= messageGroupingGapSeconds else { return false }
    return earlier.authorKeyPrefix == later.authorKeyPrefix
  }

  /// Name on cluster-start, avatar on cluster-end.
  static func incomingBookends(in messages: [RoomMessageDTO]) -> (nameIDs: Set<UUID>, avatarIDs: Set<UUID>) {
    var nameIDs: Set<UUID> = []
    var avatarIDs: Set<UUID> = []
    for (index, message) in messages.enumerated() {
      guard !message.isFromSelf else { continue }
      let previous = index > 0 ? messages[index - 1] : nil
      let next = index + 1 < messages.count ? messages[index + 1] : nil
      let continuesFromPrevious = previous.map { incomingClusterContinues(from: $0, to: message) } ?? false
      let continuesToNext = next.map { incomingClusterContinues(from: message, to: $0) } ?? false
      if !continuesFromPrevious { nameIDs.insert(message.id) }
      if !continuesToNext { avatarIDs.insert(message.id) }
    }
    return (nameIDs, avatarIDs)
  }

  static func tiledRows(
    in messages: [RoomMessageDTO],
    translations: [UUID: MessageTranslationChrome] = [:]
  ) -> [RoomTiledRow] {
    let bookends = incomingBookends(in: messages)
    return messages.enumerated().map { index, message in
      RoomTiledRow(
        message: message,
        showTimestamp: shouldShowTimestamp(at: index, in: messages),
        showSenderName: bookends.nameIDs.contains(message.id),
        showAvatar: bookends.avatarIDs.contains(message.id),
        translation: translations[message.id]
      )
    }
  }
}
