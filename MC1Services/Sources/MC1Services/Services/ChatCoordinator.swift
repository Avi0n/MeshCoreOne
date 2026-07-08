import Foundation
import OSLog

/// Per-(radio, conversation) source of truth for chat timeline state.
///
/// Replaces the parallel-storage model on `ChatViewModel`. Two
/// `ChatViewModel`s pointing at the same conversation — iPad split view,
/// sheet dismissal, navigation transitions — share one `ChatCoordinator`;
/// the registry resolves instances by `ChatConversationID`.
///
/// Owned by `ChatCoordinatorRegistry` on `ServiceContainer`. Lives for the
/// lifetime of the `ServiceContainer` (i.e., the lifetime of a single
/// connection). Tears down on disconnect.
@Observable
@MainActor
public final class ChatCoordinator {
  /// Number of messages fetched per pagination page. Used by `hardReset`
  /// to refetch the most recent slice; consumed by `ChatViewModel` for
  /// initial-load sizing so post-reset renders match the normal load.
  public static let pageSize: Int = 50

  /// Read messages loaded above the first unread so the "New Messages" divider
  /// has a little context to sit beneath rather than pinning to the very top.
  public static let dividerReadContext: Int = 12

  /// Initial fetch size for opening a conversation. Guarantees every unread
  /// message (plus a little read context) lands in the first page: otherwise a
  /// conversation with more than `pageSize` unread would place the divider on a
  /// message that only pages in later, leaving the jump-to-divider button with no
  /// materialized target to scroll to.
  public static func initialPageSize(unreadCount: Int) -> Int {
    max(pageSize, unreadCount + dividerReadContext)
  }

  public let conversationID: ChatConversationID

  @ObservationIgnored
  let logger = Logger(subsystem: "com.mc1", category: "ChatCoordinator")

  /// Canonical loaded-messages list. Mutated only inside this class;
  /// every reader either reads `messagesByID` (O(1) lookup) or
  /// `renderState.items` (the rendered timeline).
  public internal(set) var messages: [MessageDTO] = []

  /// O(1) lookup keyed by message ID. The guard at every append /
  /// update / event-handler site reads this, never `renderState`.
  ///
  /// Pairing invariant: every `messagesByID` mutation must trigger a
  /// downstream `renderState.items` change — typically via the
  /// `renderStateID` bump + off-main `rebuildItems` → `setRenderState`
  /// apply chain on `ChatCoordinator`. View-body callers reach this map
  /// only transitively through observation-tracked `renderState.items`;
  /// cells re-render when items change. Any mutation that bypasses the
  /// standard mutate-then-renderStateID-bump flow must manually
  /// invalidate `renderState.items`, or drop `@ObservationIgnored` here.
  @ObservationIgnored
  public internal(set) var messagesByID: [UUID: MessageDTO] = [:]

  /// Immutable timeline snapshot rendered by the chat table. Rebuilt
  /// from `messages` by the off-main builder; assigned on main only.
  public internal(set) var renderState: ChatRenderState = .empty

  /// Monotonic counter incremented on every mutation of `messages` or
  /// every assignment to `renderState`. The off-main build captures
  /// this at start and discards its result if the counter has advanced
  /// when it returns to main. Companion `urlDetectionGeneration` on the
  /// view model gates `cachedURLs` writes from the URL-detection writer.
  /// Consumed by the off-main builder and internal mutation tracking
  /// only — no view body reads it.
  @ObservationIgnored
  public internal(set) var renderStateID: UInt64 = 0

  /// IDs accumulated since the last load cycle. The next coalesced load
  /// drains this set atomically. `enqueueReload(updatedMessageIDs:)` is
  /// the single chokepoint for ack / retry / fail / heard-repeat /
  /// reaction events. `@ObservationIgnored` because no view reads this
  /// set directly — readers consume `renderState` after the load cycle
  /// applies — and the registrar bookkeeping on every burst-event union
  /// would be wasted work.
  @ObservationIgnored
  var pendingReloadIDs: Set<UUID> = []

  /// Whether a load cycle is currently in flight. The chase-the-counter
  /// pattern in `coalescedReload` consults this. Also
  /// `@ObservationIgnored` for the same reason as `pendingReloadIDs`.
  @ObservationIgnored
  var reloadInFlight = false

  /// Gates concurrent loads while a `hardReset` is mid-flight. The
  /// scheduler guard prevents new `coalescedReload` Tasks from starting
  /// after a hardReset begins; the loop-top check in `coalescedReload`
  /// stops the already-running Task from draining `pendingReloadIDs` and
  /// stomping the freshly-refetched post-hardReset state with stale
  /// per-ID `update(messageID:)` writes. Cleared on hardReset completion
  /// via a `defer`-driven cleanup that also schedules any buffered IDs.
  @ObservationIgnored
  var hardResetInFlight = false

  /// In-flight off-main batch build. Cancelled before each new
  /// `rebuildItems` call so successive rebuilds do not pile up concurrent
  /// work. Mirrors the cancel-and-reassign pattern used by URL detection.
  @ObservationIgnored
  public internal(set) var buildItemsTask: Task<Void, Never>?

  /// In-flight coalesced-reload drain Task. Stored so the registry can
  /// cancel it on `tearDown`, releasing the coordinator and any captured
  /// services in flight. The `reloadInFlight` flag still serves a separate
  /// concurrency purpose (break-the-running-loop semantics inside
  /// `coalescedReload`); the Task handle is purely for teardown.
  @ObservationIgnored
  public internal(set) var coalescedReloadTask: Task<Void, Never>?

  /// In-flight hardReset refetch Task. See `coalescedReloadTask` for the
  /// teardown rationale.
  @ObservationIgnored
  public internal(set) var hardResetTask: Task<Void, Never>?

  /// Data store used by `applyReloadedIDs` for per-ID fetches. Bound at
  /// construction by the registry. `@ObservationIgnored` — never read
  /// from a view body.
  @ObservationIgnored
  let dataStore: PersistenceStore

  /// Per-ID render-item rebuild hook invoked by `applyReloadedIDs` after a
  /// successful DTO refresh. The bound `ChatViewModel` rebuilds the
  /// corresponding `MessageItem` using its main-actor-only inputs
  /// (preview state, cached URLs, decoded images). Stays `nil` when no
  /// view model is bound — `applyReloadedIDs` then refreshes DTOs without
  /// rebuilding render items, which matches headless and test usage.
  /// `@ObservationIgnored` because no view body reads this closure.
  @ObservationIgnored
  public var renderItemRebuilder: (@MainActor (UUID) -> Void)?

  /// Fires when the coordinator's `renderState.items` no longer reflects
  /// canonical `messages` and the bound view model must reassemble per-message
  /// inputs (preview state, cached URLs, decoded images live on main and are
  /// owned by the VM) before `rebuildItems` can be called again. Triggered
  /// when a fresher mutation lands mid-flight and `setRenderState` rejects
  /// the stale rebuild, and after `hardReset`'s `replaceAll`.
  @ObservationIgnored
  public var renderStateInvalidated: (@MainActor () -> Void)?

  init(
    conversationID: ChatConversationID,
    dataStore: PersistenceStore
  ) {
    self.conversationID = conversationID
    self.dataStore = dataStore
  }

  #if DEBUG
    /// Test-only factory that builds a standalone coordinator backed by
    /// an in-memory `PersistenceStore`. Lets unit tests exercise mutation
    /// behaviour without bringing up a `ServiceContainer`.
    public static func makeForTesting(
      conversationID: ChatConversationID = .dm(radioID: UUID(), contactID: UUID())
    ) -> ChatCoordinator {
      // swiftlint:disable:next force_try
      let container = try! PersistenceStore.createContainer(inMemory: true)
      let store = PersistenceStore(modelContainer: container)
      return ChatCoordinator(conversationID: conversationID, dataStore: store)
    }
  #endif
}
