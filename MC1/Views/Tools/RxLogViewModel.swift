// MC1/Views/Tools/RxLogViewModel.swift
import Foundation
import MC1Services

@MainActor
@Observable
final class RxLogViewModel {
    enum RouteFilter: String, CaseIterable {
        case all
        case floodOnly
        case directOnly

        var displayName: String {
            switch self {
            case .all: L10n.Tools.Tools.RxLog.Filter.all
            case .floodOnly: L10n.Tools.Tools.RxLog.Filter.floodOnly
            case .directOnly: L10n.Tools.Tools.RxLog.Filter.directOnly
            }
        }
    }

    enum DecryptFilter: String, CaseIterable {
        case all
        case decrypted
        case failed

        var displayName: String {
            switch self {
            case .all: L10n.Tools.Tools.RxLog.Filter.all
            case .decrypted: L10n.Tools.Tools.RxLog.Filter.decrypted
            case .failed: L10n.Tools.Tools.RxLog.Filter.failed
            }
        }
    }

    private(set) var entries: [RxLogEntryDTO] = []
    private(set) var groupCounts: [String: Int] = [:]
    private(set) var routeFilter: RouteFilter = .all
    private(set) var decryptFilter: DecryptFilter = .all

    /// Maps path hash bytes (1, 2, or 3 byte prefixes) to contact display names.
    /// Only populated for prefixes that uniquely identify a single contact.
    private(set) var nodeNames: [Data: String] = [:]

    private var streamTask: Task<Void, Never>?
    private var rxLogService: RxLogService?

    func setRouteFilter(_ filter: RouteFilter) {
        routeFilter = filter
    }

    func setDecryptFilter(_ filter: DecryptFilter) {
        decryptFilter = filter
    }

    /// Entries filtered by current filter settings.
    var filteredEntries: [RxLogEntryDTO] {
        entries.filter { entry in
            // Route filter
            switch routeFilter {
            case .all: break
            case .floodOnly:
                guard entry.isFlood else { return false }
            case .directOnly:
                guard !entry.isFlood else { return false }
            }

            // Decrypt filter
            switch decryptFilter {
            case .all: break
            case .decrypted:
                guard entry.decryptStatus == .success else { return false }
            case .failed:
                guard entry.decryptStatus == .hmacFailed
                    || entry.decryptStatus == .decryptFailed
                    || entry.decryptStatus == .noMatchingKey
                    || entry.decryptStatus == .dmNoMatchingKey else { return false }
            }

            return true
        }
    }

    /// Subscribe to RxLogService for updates while view is visible.
    func subscribe(to service: RxLogService) async {
        // If service changed, reset state
        if rxLogService !== service {
            unsubscribe()
            entries.removeAll()
            groupCounts.removeAll()
        }

        rxLogService = service
        entries = await service.loadExistingEntries()
        rebuildGroupCounts()

        streamTask = Task {
            for await entry in await service.entryStream() {
                guard !Task.isCancelled else { break }
                appendEntry(entry)
            }
        }
    }

    /// Stop listening to updates.
    func unsubscribe() {
        streamTask?.cancel()
        streamTask = nil
    }

    /// Clear all log entries.
    func clearLog() async {
        await rxLogService?.clearEntries()
        entries.removeAll()
        groupCounts.removeAll()
    }

    // MARK: - Incremental Updates

    private func appendEntry(_ entry: RxLogEntryDTO) {
        // Insert at front to maintain newest-first order (matching DB fetch sort)
        entries.insert(entry, at: 0)
        groupCounts[entry.packetHash, default: 0] += 1

        // Prune oldest (now at end) if over cap
        if entries.count > 1000 {
            let removed = entries.removeLast()
            groupCounts[removed.packetHash, default: 1] -= 1
            if groupCounts[removed.packetHash] == 0 {
                groupCounts.removeValue(forKey: removed.packetHash)
            }
        }
    }

    private func rebuildGroupCounts() {
        groupCounts = Dictionary(grouping: entries, by: \.packetHash)
            .mapValues(\.count)
    }

    // MARK: - Node Name Resolution

    /// Load contact names for path hop resolution.
    func loadNodeNames(from dataStore: some PersistenceStoreProtocol, deviceID: UUID) async {
        do {
            let contacts = try await dataStore.fetchContacts(deviceID: deviceID)
            nodeNames = Self.buildNodeNameMap(from: contacts)
        } catch {
            nodeNames = [:]
        }
    }

    /// Build a map from public key prefixes (1, 2, 3 bytes) to display names.
    /// Only stores entries where the prefix uniquely identifies a single contact.
    static func buildNodeNameMap(from contacts: [ContactDTO]) -> [Data: String] {
        var map: [Data: String] = [:]

        for prefixLength in 1...3 {
            var prefixCounts: [Data: (name: String, count: Int)] = [:]

            for contact in contacts {
                guard contact.publicKey.count >= prefixLength else { continue }
                let prefix = contact.publicKey.prefix(prefixLength)

                if let existing = prefixCounts[prefix] {
                    prefixCounts[prefix] = (existing.name, existing.count + 1)
                } else {
                    prefixCounts[prefix] = (contact.displayName, 1)
                }
            }

            for (prefix, entry) in prefixCounts where entry.count == 1 {
                map[prefix] = entry.name
            }
        }

        return map
    }
}
