import Foundation
import MC1Services

/// Re-primes warm `ChatCoordinator`s when a message arrives for a conversation
/// that is not currently open.
///
/// A coordinator prewarmed by `AppState.prefetchConversation` keeps the tail it
/// had at prime time: nothing updates it while its chat is closed, because the
/// message event stream only feeds the open conversation's view model.
/// Reopening then renders the stale list on the first frame, and the fresh
/// fetch lands in the tiled list as an offset-preserving tail append, leaving
/// the view scrolled above the new messages instead of at the bottom.
/// Re-priming at arrival time keeps the warm tail current, so a reopen's first
/// frame is already complete and bottom-anchored.
///
/// Owned by `AppState` (see `ensureChatPrewarmRefresher`), fed by
/// `MessageEventDispatcher`. Rooms have no coordinator and need no refresh.
@MainActor
final class ChatPrewarmRefresher {
  /// Identifies the conversation a message event belongs to, carrying just
  /// enough to key the coordinator lookup up front and resolve the full
  /// conversation lazily after the debounce.
  enum ConversationKind {
    case dm(contact: ContactDTO)
    case channel(radioID: UUID, channelIndex: UInt8)

    var coordinatorID: ChatConversationID {
      switch self {
      case let .dm(contact):
        .dm(radioID: contact.radioID, contactID: contact.id)
      case let .channel(radioID, channelIndex):
        .channel(radioID: radioID, channelIndex: channelIndex)
      }
    }
  }

  struct Hooks {
    /// Registry holding the warm coordinators; nil while no store is available.
    var registry: @MainActor () -> ChatCoordinatorRegistry?
    /// Dependency bundle for the priming path; nil when the owner is gone.
    var dependencies: @MainActor () -> ChatTimelinePrimer.Dependencies?
    /// Environment snapshot to bake items with; nil when no chat UI has
    /// rendered yet (nothing can be warm then, so skipping is safe).
    var envInputs: @MainActor (ChatConversationType) -> EnvInputs?
    /// Whether the conversation is currently open. The open view model already
    /// appends arrivals in place; re-priming under it would race its
    /// optimistic rows.
    var isConversationActive: @MainActor (ConversationKind) -> Bool
    /// Whether the app is currently in the foreground. Background arrivals evict
    /// closed warm coordinators instead of priming them.
    var isInForeground: @MainActor () -> Bool
    /// Resolves the channel DTO for a channel event's radio + slot index.
    var channel: @MainActor (UUID, UInt8) async -> ChannelDTO?
    /// Resolves the contact DTO for a DM event's radio + contact id. Re-fetched
    /// at refresh time so the bake reads the post-increment unread count, not the
    /// pre-increment DTO captured when the event was dispatched.
    var contact: @MainActor (UUID, UUID) async -> ContactDTO?
    /// Link-preview cache for the primer, so a refresh also warms preview
    /// metadata and hero dimensions for the fresh tail; nil skips preview
    /// warming.
    var linkPreviewCache: @MainActor () -> (any LinkPreviewCaching)?
  }

  private let hooks: Hooks

  /// Coalescing window: a sync catch-up delivers a burst of messages for the
  /// same conversation; one refresh at the end of the window covers them all.
  private let debounce: Duration

  private struct PendingRefresh {
    let token: UUID
    let task: Task<Void, Never>
  }

  private var pendingRefreshes: [ChatConversationID: PendingRefresh] = [:]

  /// One scheduled refresh per conversation; an arrival during the window
  /// rides the refresh already scheduled.
  var inFlight: [ChatConversationID: Task<Void, Never>] {
    pendingRefreshes.mapValues(\.task)
  }

  init(hooks: Hooks, debounce: Duration = .milliseconds(250)) {
    self.hooks = hooks
    self.debounce = debounce
  }

  func noteDirectMessage(contact: ContactDTO) {
    schedule(.dm(contact: contact))
  }

  func noteChannelMessage(radioID: UUID, channelIndex: UInt8) {
    schedule(.channel(radioID: radioID, channelIndex: channelIndex))
  }

  func handleEnterBackground() {
    let entries = pendingRefreshes
    pendingRefreshes.removeAll()
    let registry = hooks.registry()
    for (id, entry) in entries {
      entry.task.cancel()
      registry?.remove(for: id)
    }
  }

  func cancelAll() {
    for entry in pendingRefreshes.values {
      entry.task.cancel()
    }
    pendingRefreshes.removeAll()
  }

  func cancel(for id: ChatConversationID) {
    pendingRefreshes.removeValue(forKey: id)?.task.cancel()
  }

  private func ensureWarmForegroundRegistry(
    for kind: ConversationKind,
    id: ChatConversationID
  ) -> ChatCoordinatorRegistry? {
    guard !Task.isCancelled,
          !hooks.isConversationActive(kind),
          let registry = hooks.registry()
    else { return nil }

    let isLoaded = registry.existingCoordinator(for: id)?.renderState.phase == .loaded
    guard hooks.isInForeground() else {
      pendingRefreshes.removeValue(forKey: id)?.task.cancel()
      if isLoaded {
        registry.remove(for: id)
      }
      return nil
    }
    return isLoaded ? registry : nil
  }

  private func schedule(_ kind: ConversationKind) {
    let id = kind.coordinatorID
    guard ensureWarmForegroundRegistry(for: kind, id: id) != nil,
          pendingRefreshes[id] == nil
    else { return }

    let debounce = debounce
    let token = UUID()
    let task = Task { [weak self] in
      defer {
        if let self, self.pendingRefreshes[id]?.token == token {
          self.pendingRefreshes[id] = nil
        }
      }
      try? await Task.sleep(for: debounce)
      guard let self, !Task.isCancelled else { return }
      await self.refresh(kind, id: id)
    }
    pendingRefreshes[id] = PendingRefresh(token: token, task: task)
  }

  private func refresh(_ kind: ConversationKind, id: ChatConversationID) async {
    // Re-validate after the debounce: the user may have opened the chat, the
    // registry may have torn down on disconnect, or the LRU may have evicted
    // the entry. A cold entry needs no refresh; the next open fetches fresh.
    guard ensureWarmForegroundRegistry(for: kind, id: id) != nil else {
      return
    }

    // Re-fetch the DM contact so the divider bakes from the current unread count
    // (which also sizes the first page from that count); fall back to the
    // event-time DTO if the store lookup misses.
    let conversation: ChatConversationType? = switch kind {
    case let .dm(contact):
      await hooks.contact(contact.radioID, contact.id).map(ChatConversationType.dm) ?? .dm(contact)
    case let .channel(radioID, channelIndex):
      await hooks.channel(radioID, channelIndex).map(ChatConversationType.channel)
    }
    guard ensureWarmForegroundRegistry(for: kind, id: id) != nil,
          let conversation,
          let dependencies = hooks.dependencies(),
          let envInputs = hooks.envInputs(conversation)
    else { return }

    // A `.prime` bind is denied while an interactive owner is active (the
    // denied bind aborts inside `prime`). A mid-refresh open revokes the
    // writer and remaining writes no-op.
    let primer = ChatTimelinePrimer(
      dependencies: dependencies,
      linkPreviewCache: hooks.linkPreviewCache()
    )
    await primer.prime(conversation, envInputs: envInputs)
  }
}
