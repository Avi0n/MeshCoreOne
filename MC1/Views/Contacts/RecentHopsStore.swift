import Foundation
import MC1Services

/// Per-radio LRU of recently added hop public keys, persisted to `UserDefaults`.
/// Most-recent first, capped at ``limit``. Shared by the contact path editor and
/// the trace path builder so both surfaces present the same "Recent" section.
///
/// The owning view model holds the observable `[Data]` array; this type owns only
/// load/persist and the LRU transform so SwiftUI observation stays on the view model.
struct RecentHopsStore {
    /// Frozen storage-key prefix. Existing contact-side recents are persisted under
    /// this exact key and must keep loading after the trace side adopts the store.
    private static let keyPrefix = "pathEdit.recentPublicKeys."
    static let limit = 8

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    static func defaultsKey(for radioID: UUID) -> String {
        "\(keyPrefix)\(radioID.uuidString)"
    }

    /// Load the persisted recents for `radioID`, newest first.
    func load(for radioID: UUID) -> [Data] {
        let hexList = defaults.stringArray(forKey: Self.defaultsKey(for: radioID)) ?? []
        return hexList.compactMap(Data.init(hexString:))
    }

    /// LRU-insert `pubkey` into `current`, persist for `radioID`, and return the new list.
    /// Moves an existing key to the front rather than duplicating; trims to ``limit``.
    /// Stores lowercase hex (`Data.hex`), which `Data(hexString:)` round-trips case-insensitively.
    func record(_ pubkey: Data, into current: [Data], for radioID: UUID) -> [Data] {
        var updated = current
        updated.removeAll { $0 == pubkey }
        updated.insert(pubkey, at: 0)
        if updated.count > Self.limit {
            updated = Array(updated.prefix(Self.limit))
        }
        defaults.set(updated.map(\.hex), forKey: Self.defaultsKey(for: radioID))
        return updated
    }
}
