import Foundation

/// Disk-backed store for per-conversation chat input drafts.
///
/// Persists the text a user typed into the chat composer but did not send, so it
/// survives leaving and re-entering a conversation and survives an app restart.
/// Backed by `UserDefaults` under a single dictionary key, keyed by
/// ``ChatConversationID/draftStorageKey``. No view observes this store, so it is
/// deliberately not `@Observable`.
///
/// Drafts are not pruned by design: the residual set is bounded by the
/// conversations a user drafted in and then abandoned, negligible at text sizes.
/// Entries are removed when a draft is sent, when the composer is emptied, and
/// when a channel slot is vacated (channel delete, sync prune, backup-import
/// relocation) so a reused slot can't surface the prior channel's draft.
///
/// Draft content inherits the app container's default data-protection class —
/// neither this store nor the SwiftData message store sets an explicit class, so
/// on iOS both default to until-first-unlock.
///
/// Drafts are device-local and intentionally excluded from the backup envelope.
/// Do not add a `BackupUserDefaults` mapping for ``storageKey``.
@MainActor
public final class DraftStore {

    /// Top-level `UserDefaults` key holding the `[draftStorageKey: text]` map.
    public static let storageKey = "chat.drafts.v1"

    private let defaults: UserDefaults
    private var cache: [String: String]

    /// - Parameter defaults: Injectable for tests; production uses `.standard`.
    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.cache = (defaults.dictionary(forKey: Self.storageKey) as? [String: String]) ?? [:]
    }

    /// The stored draft for a conversation, or `nil` if none.
    public func draft(for id: ChatConversationID) -> String? {
        cache[id.draftStorageKey]
    }

    /// Stores `text` as the draft for a conversation, or removes the entry when
    /// `text` is empty or whitespace/newline-only. Trimming is used only for the
    /// emptiness test; a non-empty draft (e.g. with a trailing newline) is stored
    /// verbatim.
    public func setDraft(_ text: String, for id: ChatConversationID) {
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            clearDraft(for: id)
        } else {
            cache[id.draftStorageKey] = text
            persist()
        }
    }

    /// Removes the draft for a conversation, if present.
    public func clearDraft(for id: ChatConversationID) {
        guard cache.removeValue(forKey: id.draftStorageKey) != nil else { return }
        persist()
    }

    /// Removes the drafts for the given channel slots on `radioID`, persisting once for
    /// the whole batch. Used by the slot-vacate paths (channel delete, sync prune) so a
    /// channel later reusing a freed slot can't surface the prior channel's draft.
    public func clearChannelDrafts(radioID: UUID, indices: Set<UInt8>) {
        clearChannelDrafts(slotsByRadio: [radioID: indices])
    }

    /// Removes the drafts for the given channel slots grouped by radio, persisting once for
    /// the whole batch. Used by the backup-import path, where affected slots arrive per radio.
    public func clearChannelDrafts(slotsByRadio: [UUID: Set<UInt8>]) {
        var didChange = false
        for (radioID, indices) in slotsByRadio {
            for index in indices {
                let key = ChatConversationID.channel(radioID: radioID, channelIndex: index).draftStorageKey
                if cache.removeValue(forKey: key) != nil {
                    didChange = true
                }
            }
        }
        if didChange {
            persist()
        }
    }

    /// Pure restore decision: returns the saved draft only when the composer is
    /// empty, otherwise `nil`. Keeps a reconnect-driven re-restore (or a double
    /// initial load) from clobbering text the user is already typing, and isolates
    /// that guard so it is unit-testable independent of the view.
    public func draftToApply(over currentText: String, for id: ChatConversationID) -> String? {
        currentText.isEmpty ? draft(for: id) : nil
    }

    private func persist() {
        defaults.set(cache, forKey: Self.storageKey)
    }
}
