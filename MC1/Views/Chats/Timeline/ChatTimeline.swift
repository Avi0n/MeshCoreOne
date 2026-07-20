import Foundation
import MC1Services
import OSLog

/// Per-conversation assembly module. Owns the bake state, the write
/// capability, and the populate → divider → filter → write → bake ordering,
/// so callers get `open`/`rebake` verbs instead of hand-sequencing raw
/// writer mutations.
///
/// Role-parameterized: the interactive conversation (`ChatViewModel`) and the
/// speculative warm (`ChatTimelinePrimer`) drive one implementation, differing
/// only in the `ChatWriterRole` they bind with and the hooks they supply.
/// The writer/generation anti-clobber machinery stays in `ChatCoordinator`;
/// this module consumes it.
@Observable
@MainActor
final class ChatTimeline {
  /// Reaction-indexing inputs for `open`. The row-rebake hook is this
  /// timeline's own; callers supply only what varies.
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

  /// Write capability minted by the last successful `bind`; nil when unbound
  /// or when a `.prime` bind was denied. Stale writers no-op at the
  /// coordinator, so holding one across a supersede is safe.
  @ObservationIgnored
  private(set) var writer: ChatTimelineWriter?

  /// Environment-derived inputs baked into every `MessageItem`.
  var envInputs: EnvInputs = .default

  /// Conversation this timeline is assembling. Captured by `stageOpen` and
  /// `open`; owners that refresh its DTO assign it directly. Paging fetches
  /// key off its IDs, which are stable across DTO refreshes.
  @ObservationIgnored
  var conversation: ChatConversationType?

  /// Unread count captured when the open was staged, before any clearing
  /// side effect runs. Gates whether the open expects a divider at all.
  private(set) var openUnreadCount = 0

  /// Whether the view has consumed the one-shot open positioning.
  private(set) var anchorConsumed = false

  /// Latches once the initial populate has finished (any outcome), so a
  /// load that produced no divider target (already read, failed fetch)
  /// presents the timeline instead of withholding it forever. Set only by
  /// `stageOpen` and `open`.
  var initialLoadSettled = false

  /// Live per-connection store; nil while disconnected (offline browse).
  @ObservationIgnored
  var dataStoreProvider: @MainActor () -> DataStore? = { nil }

  /// Live sender tables for the bake; owners supply contacts they observe.
  @ObservationIgnored
  var senderTablesProvider: @MainActor () -> ChatSenderTables = { .empty }

  /// Runs after each full-timeline bake applies (the interactive owner's
  /// legacy preview decode); nil for primes.
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

  /// Attaches the shared coordinator for reads only, so a warm conversation
  /// renders on the first frame before the load task binds. Never touches the
  /// writer slot or hooks, so it is safe from view `init`, where transient
  /// instances may be created and discarded.
  func attach(_ coordinator: ChatCoordinator) {
    self.coordinator = coordinator
  }

  /// Claims the coordinator's writer slot for this timeline's role and
  /// installs the rebake hooks as one atomic act. Returns false when the
  /// bind was denied (a `.prime` against a live interactive owner); the
  /// coordinator stays attached for reads either way.
  ///
  /// Hooks capture this timeline weakly: when the owning view model or
  /// primer is discarded, the timeline goes with it and a late invalidation
  /// no-ops; the next open's full rebuild repairs the items.
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

  /// Vacates the coordinator's writer slot so arrival-time prime refreshes
  /// can service this conversation while it is off screen; the next `bind`
  /// reclaims it. Deallocation is not a substitute: SwiftUI can keep a
  /// popped destination's state alive.
  func releaseWriter() {
    if let coordinator {
      coordinator.releaseWriter(owner: self)
    }
    writer = nil
  }

  #if DEBUG
    /// Test seam: adopts an externally minted writer/coordinator pair so
    /// suites can bind with a chosen role and custom hooks, mirroring
    /// pre-bind states `bind` cannot produce (e.g. a stale prime holder).
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
    // When the network transitions from unavailable to available, drop the
    // sticky map-snapshot failures so renders that failed during the outage
    // retry on the next rebuild. Without this, an offline-pack miss stays
    // poisoned until a memory warning evicts the failed set.
    if envInputs.isOffline, !new.isOffline {
      MapSnapshotStore.shared.clearFailures()
    }
    envInputs = new
    // The environment feeds every formatting input, so its cached output is
    // now stale for all rows and must be rebuilt under the new appearance.
    bake.formattedTextCache.removeAll()
    guard !messages.isEmpty else { return }
    rebakeAll()
  }

  // MARK: - Open anchor

  /// Stages a conversation for opening: captures the unread count the
  /// anchor decision keys on and resets the per-open latches and divider so
  /// the anchor can only come from this session's bake, never one a shared
  /// coordinator carries over from a previous open.
  func stageOpen(_ conversation: ChatConversationType) {
    self.conversation = conversation
    openUnreadCount = conversation.unreadCount
    anchorConsumed = false
    initialLoadSettled = false
    bake.newMessagesDividerMessageID = nil
    bake.dividerComputed = false
  }

  /// First-snapshot decision for the staged open; see
  /// `ChatInitialScrollPolicy`. `bake` is not observable, but its divider
  /// only moves alongside a coordinator mutation, so the observable
  /// `itemIndexByID` read and the latch properties re-evaluate this whenever
  /// a decision input has changed.
  var firstSnapshot: ChatInitialScrollPolicy.FirstSnapshotDecision {
    ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: anchorConsumed,
      unreadCount: openUnreadCount,
      initialLoadSettled: initialLoadSettled,
      dividerMessageID: bake.newMessagesDividerMessageID,
      itemIndexByID: itemIndexByID
    )
  }

  /// Marks the one-shot open positioning as spent. Called when the view's
  /// list has positioned on the anchor; later snapshots present freely.
  func consumeAnchor() {
    anchorConsumed = true
  }
}
