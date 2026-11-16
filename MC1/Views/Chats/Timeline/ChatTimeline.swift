import Foundation
import MC1Services
import OSLog

/// Assembles one conversation's timeline and owns Populate and Bake ordering.
/// Writer authority stays with ChatCoordinator for interactive opens and Prime.
@Observable
@MainActor
final class ChatTimeline {
  struct ReactionIndexing {
    let service: ReactionService
    let scope: ReactionIndexScope
  }

  @ObservationIgnored
  let logger = Logger(subsystem: "com.mc1", category: "ChatTimeline")

  /// Role every `bind` claims the coordinator's writer slot with.
  @ObservationIgnored
  let role: ChatWriterRole

  /// Per-message bake state (preview/image caches, divider) feeding item
  /// builds. Not observed: redraw is decided by the `Equatable MessageItem`.
  @ObservationIgnored
  let bake = ChatMessageBakeState()

  /// Shared per-conversation source of truth. Set by `attach` (read-only,
  /// safe pre-`configure`) or `bind` (with write capability).
  private(set) var coordinator: ChatCoordinator?

  /// The last bound write capability; stale writers no-op at the coordinator.
  @ObservationIgnored
  private(set) var writer: ChatTimelineWriter?

  /// Environment-derived inputs baked into every `MessageItem`.
  var envInputs: EnvInputs = .default

  /// DTO refreshes keep the same conversation IDs, so paging can use the latest value.
  @ObservationIgnored
  var conversation: ChatConversationType?

  private(set) var openAnchorState = ChatOpenAnchorState(unreadCount: 0)

  #if DEBUG
    /// When set, `open` returns populate's failure contract without a process-global hook.
    var testPopulateError: Error?

    /// Awaited in `loadOlder` after the fetch so a test can queue populate behind it.
    @ObservationIgnored
    var loadOlderInterleaveHook: (@MainActor () async -> Void)?

    /// When set, `loadOlder` throws this after the interleave hook.
    var loadOlderTestError: Error?

    /// When set, populate throws this after the entry spinner clear.
    var testPopulateFetchError: Error?
  #endif

  /// Process-lifetime store when a radio has been paired. Nil until first pair.
  @ObservationIgnored
  var dataStoreProvider: @MainActor () -> DataStore? = { nil }

  /// Live sender tables for the bake; owners supply contacts they observe.
  @ObservationIgnored
  var senderTablesProvider: @MainActor () -> ChatSenderTables = { .empty }

  /// Runs after a full-timeline Bake applies; Prime leaves it nil.
  @ObservationIgnored
  var postApply: (@MainActor () -> Void)?

  init(role: ChatWriterRole) {
    self.role = role
  }

  // MARK: - Reads

  var messages: [MessageDTO] {
    coordinator?.messages ?? []
  }

  var messagesByID: [UUID: MessageDTO] {
    coordinator?.messagesByID ?? [:]
  }

  var renderState: ChatRenderState {
    coordinator?.renderState ?? .empty
  }

  var items: [MessageItem] {
    renderState.items
  }

  var itemIndexByID: [UUID: Int] {
    renderState.itemIndexByID
  }

  // MARK: - Binding

  /// Exposes cached messages before binding without claiming writer authority,
  /// so transient view instances cannot displace a live owner.
  func attach(_ coordinator: ChatCoordinator) {
    self.coordinator = coordinator
  }

  /// Claims this timeline's writer role and installs weakly captured Bake hooks.
  /// A denied Prime retains read access; hooks no-op when their owner is gone.
  @discardableResult
  func bind(
    _ coordinator: ChatCoordinator,
    dataStore: @escaping @MainActor () -> DataStore?,
    senderTables: @escaping @MainActor () -> ChatSenderTables,
    postApply: (@MainActor () -> Void)?
  ) -> Bool {
    dataStoreProvider = dataStore
    senderTablesProvider = senderTables
    self.postApply = postApply
    writer = coordinator.bindWriter(
      owner: self,
      role: role,
      renderItemRebuilder: { [weak self] messageID in
        self?.rebakeRow(messageID)
      },
      renderStateInvalidated: { [weak self] in
        self?.rebakeAll()
      }
    )
    self.coordinator = coordinator
    return writer != nil
  }

  /// Releases writer authority for Prime while off screen, because SwiftUI can
  /// retain a popped destination's state beyond its visible lifetime.
  func releaseWriter() {
    if let coordinator {
      coordinator.releaseWriter(owner: self)
    }
    writer = nil
  }

  #if DEBUG
    /// Adopts a writer with custom hooks so tests can represent a stale holder.
    func adoptForTesting(coordinator: ChatCoordinator, writer: ChatTimelineWriter?) {
      self.coordinator = coordinator
      self.writer = writer
    }
  #endif

  // MARK: - Environment

  /// Update env-derived inputs and rebake everything when the value changes
  /// and there are messages to rebake. Idempotent on no-change.
  func applyEnvInputs(_ new: EnvInputs) {
    guard envInputs != new else { return }
    // Retry cached map-snapshot failures when the network returns, rather than
    // waiting for memory pressure to evict failures from the outage.
    if envInputs.isOffline, !new.isOffline {
      MapSnapshotStore.shared.clearFailures()
    }
    envInputs = new
    // Cached formatting depends on the environment, so every row must be rebuilt.
    bake.formattedTextCache.removeAll()
    guard !messages.isEmpty else { return }
    rebakeAll()
  }

  // MARK: - Open anchor

  /// Captures this open's unread count and resets its divider so the Anchor cannot
  /// come from a previous open's cached rows.
  func stageOpen(_ conversation: ChatConversationType) {
    self.conversation = conversation
    openAnchorState = ChatOpenAnchorState(unreadCount: conversation.unreadCount)
    bake.newMessagesDividerMessageID = nil
    bake.dividerComputed = false
  }

  func completePopulate(_ outcome: ChatTimelinePopulator.Outcome) {
    openAnchorState.completePopulate(outcome)
  }

  /// Bake's divider changes alongside coordinator mutations, so observing the
  /// current items and open state keeps this decision current.
  var firstSnapshot: ChatOpenAnchorState.FirstSnapshotDecision {
    let dividerMessageID = bake.newMessagesDividerMessageID
    let dividerRowOnScreen = dividerMessageID.flatMap { id -> Bool? in
      guard let index = itemIndexByID[id], items.indices.contains(index) else { return nil }
      return items[index].grouping.showNewMessagesDivider
    } ?? false
    return openAnchorState.firstSnapshot(
      dividerMessageID: dividerMessageID,
      dividerRowOnScreen: dividerRowOnScreen
    )
  }

  /// Marks the one-shot open positioning as spent. Called when the view's
  /// list has positioned on the anchor; later snapshots present freely.
  func consumeAnchor() {
    openAnchorState.consumeAnchor()
  }
}
