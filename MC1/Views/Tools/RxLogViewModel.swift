import Foundation
import MC1Services

@Observable
@MainActor
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

  // MARK: - Dependencies

  private var rxLogServiceProvider: @MainActor () -> RxLogService? = { nil }
  private var dataStoreProvider: @MainActor () -> (any PersistenceStoreProtocol)? = { nil }
  private var radioIDProvider: @MainActor () -> UUID? = { nil }

  private var rxLogService: RxLogService? {
    rxLogServiceProvider()
  }

  private var dataStore: (any PersistenceStoreProtocol)? {
    dataStoreProvider()
  }

  private var radioID: UUID? {
    radioIDProvider()
  }

  /// The provider reads live state, so change detection needs the instance
  /// seen at the previous subscribe. Weak, so a torn-down container's
  /// deallocated service reads as a change.
  private weak var subscribedService: RxLogService?

  /// Each provider is read live at its point of use; a provider returning
  /// nil mirrors a disconnected state, so unconfigured calls are no-ops.
  func configure(
    rxLogService: @escaping @MainActor () -> RxLogService?,
    dataStore: @escaping @MainActor () -> (any PersistenceStoreProtocol)?,
    radioID: @escaping @MainActor () -> UUID?
  ) {
    rxLogServiceProvider = rxLogService
    dataStoreProvider = dataStore
    radioIDProvider = radioID
  }

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

  /// Subscribe to the live RxLogService for updates while view is visible.
  func subscribe() async {
    // Cancel any existing stream task so a re-subscribe (a `.task(id:)` re-fire
    // against the same service) can't leave two streams appending each packet twice.
    unsubscribe()

    guard let service = rxLogService else { return }

    // If service changed, reset state
    if subscribedService !== service {
      entries.removeAll()
      groupCounts.removeAll()
    }
    subscribedService = service

    entries = await service.loadExistingEntries()
    rebuildGroupCounts()

    streamTask = Task {
      for await entry in service.entryStream() {
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

  /// Load contact names for path hop resolution. Leaves `nodeNames`
  /// unchanged while disconnected.
  func loadNodeNames() async {
    guard let dataStore, let radioID else { return }
    do {
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
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
