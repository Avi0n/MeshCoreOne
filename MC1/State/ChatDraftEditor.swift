import Foundation
import MC1Services
import Observation

/// Live unsent text for one DM or channel. A retired editor rejects writes so a
/// retained view cannot change the replacement conversation.
@Observable
@MainActor
final class ChatDraftEditor {
  /// Quiet period after the last keystroke before the draft is persisted,
  /// so rapid typing coalesces into a single write.
  private static let draftSaveDebounce: Duration = .milliseconds(500)

  let conversationID: ChatConversationID

  private(set) var text: String

  @ObservationIgnored private let store: DraftStore
  @ObservationIgnored private let clock: any Clock<Duration>
  @ObservationIgnored private var isDirty = false
  @ObservationIgnored private var isRetired = false
  @ObservationIgnored private var saveTask: Task<Void, Never>?

  init(conversationID: ChatConversationID, store: DraftStore, clock: (any Clock<Duration>)? = nil) {
    self.conversationID = conversationID
    self.store = store
    self.clock = clock ?? ContinuousClock()
    text = store.draft(for: conversationID) ?? ""
  }

  func setText(_ newText: String) {
    guard !isRetired, text != newText else { return }
    text = newText
    isDirty = true
    scheduleSave()
  }

  /// An empty reply is consumed and does not change `text`. A retired editor returns
  /// before consuming so the reply stays for the replacement conversation.
  func importPendingReply(consuming consume: () -> String?) {
    guard !isRetired else { return }
    guard let reply = consume() else { return }
    if reply.isEmpty { return }
    setText(reply)
  }

  func flush() {
    saveTask?.cancel()
    saveTask = nil
    saveIfStillDirty()
  }

  func reset() {
    saveTask?.cancel()
    saveTask = nil
    guard !isRetired else { return }
    text = ""
    isDirty = false
    store.clearDraft(for: conversationID)
  }

  func retire() {
    saveTask?.cancel()
    saveTask = nil
    isRetired = true
  }

  #if DEBUG
    var saveTaskForTesting: Task<Void, Never>? {
      saveTask
    }
  #endif

  private func scheduleSave() {
    saveTask?.cancel()
    saveTask = Task { [weak self, clock] in
      try? await clock.sleep(for: Self.draftSaveDebounce)
      guard !Task.isCancelled else { return }
      self?.saveIfStillDirty()
    }
  }

  /// Reads `text` only after the retirement and dirty checks, so a resumed task
  /// cannot persist a keystroke captured when it was scheduled.
  private func saveIfStillDirty() {
    guard !isRetired, isDirty else { return }
    store.setDraft(text, for: conversationID)
    isDirty = false
  }
}
