import Foundation
import MC1Services

@Observable
@MainActor
final class RxLogViewModel {
  /// In-memory cap for both the displayed list and the paused buffer.
  static let maxEntries = 1000

  private(set) var entries: [RxLogEntryDTO] = []
  private(set) var groupCounts: [String: Int] = [:]

  /// Arrivals held while the list is paused, oldest first.
  private(set) var pendingEntries: [RxLogEntryDTO] = []
  private(set) var isUserPaused = false
  private(set) var isScrolledAway = false

  var isPaused: Bool {
    isUserPaused || isScrolledAway
  }

  /// Snapshot of the packet shown in the sheet, so pruning cannot remove it.
  var selectedEntry: RxLogEntryDTO? {
    didSet { syncSelectedEntryArrivals() }
  }

  /// Arrivals sharing `selectedEntry.packetHash`, updated only when that packet's
  /// arrivals change so unrelated live packets do not invalidate the detail sheet.
  private(set) var selectedEntryArrivals: [RxLogEntryDTO] = []

  var filter: RxLogFilter {
    didSet {
      guard filter != oldValue else { return }
      defaults.set(filter.storageString, forKey: AppStorageKey.rxLogFilter.rawValue)
    }
  }

  var searchText = "" {
    didSet { rebuildMatchingNodePrefixes() }
  }

  /// Maps path hash bytes (1, 2, or 3 byte prefixes) to contact display names.
  /// Only populated for prefixes that uniquely identify a single contact.
  private(set) var nodeNames: [Data: String] = [:] {
    didSet { rebuildMatchingNodePrefixes() }
  }

  /// `nodeNames` keys whose name matches `searchText`, so search tests hops by
  /// set membership instead of resolving every hop of every entry.
  private var matchingNodePrefixes: Set<Data> = []

  private let defaults: UserDefaults
  private var streamTask: Task<Void, Never>?
  /// Bumped by every subscribe and unsubscribe so a load that resumes after
  /// hide cannot install a stream for a subscription that already ended.
  private var subscribeGeneration: UInt = 0

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
    filter = defaults.string(forKey: AppStorageKey.rxLogFilter.rawValue)
      .flatMap(RxLogFilter.init(storageString:)) ?? RxLogFilter()
  }

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

  // MARK: - Filtering and Search

  var hasActiveFilters: Bool {
    filter.hidesPackets || !searchText.isEmpty
  }

  /// Grouping is a display mode, not a filter, so it survives.
  func clearFilters() {
    filter = RxLogFilter(groupsArrivals: filter.groupsArrivals)
    searchText = ""
  }

  var visibleEntries: [RxLogEntryDTO] {
    let matching = entries.filter(isVisible)
    guard filter.groupsArrivals else { return matching }
    var seen = Set<String>()
    return matching.filter { seen.insert($0.packetHash).inserted }
  }

  var pendingVisibleCount: Int {
    pendingEntries.count(where: isVisible)
  }

  private func syncSelectedEntryArrivals() {
    let next: [RxLogEntryDTO] = if let selected = selectedEntry,
                                   groupCounts[selected.packetHash, default: 0] > 0 {
      entries.filter { $0.packetHash == selected.packetHash }
    } else {
      []
    }
    if selectedEntryArrivals != next {
      selectedEntryArrivals = next
    }
  }

  private func isVisible(_ entry: RxLogEntryDTO) -> Bool {
    filter.matches(entry) && matchesSearch(entry)
  }

  private func matchesSearch(_ entry: RxLogEntryDTO) -> Bool {
    let query = searchText.trimmingCharacters(in: .whitespaces)
    guard !query.isEmpty else { return true }

    if entry.packetHash.range(of: query, options: [.caseInsensitive, .anchored]) != nil {
      return true
    }
    let textFields = [entry.decodedText, entry.channelName, entry.fromContactName, entry.payloadType.displayName]
    if textFields.contains(where: { $0?.localizedStandardContains(query) == true }) {
      return true
    }
    guard !matchingNodePrefixes.isEmpty else { return false }
    let nodeHashes = entry.pathHopHashes + [entry.senderPrefix, entry.recipientPrefix].compactMap(\.self)
    return nodeHashes.contains(where: matchingNodePrefixes.contains)
  }

  private func rebuildMatchingNodePrefixes() {
    let query = searchText.trimmingCharacters(in: .whitespaces)
    guard !query.isEmpty else {
      matchingNodePrefixes = []
      return
    }
    matchingNodePrefixes = Set(nodeNames.filter { $0.value.localizedStandardContains(query) }.keys)
  }

  // MARK: - Live / Paused

  func setScrolledAway(_ scrolledAway: Bool) {
    guard scrolledAway != isScrolledAway else { return }
    isScrolledAway = scrolledAway
    flushPendingIfLive()
  }

  func toggleUserPause() {
    isUserPaused.toggle()
    flushPendingIfLive()
  }

  private func flushPendingIfLive() {
    guard !isPaused, !pendingEntries.isEmpty else { return }
    entries = Array((pendingEntries.reversed() + entries).prefix(Self.maxEntries))
    pendingEntries.removeAll()
    rebuildGroupCounts()
  }

  /// Drops held arrivals, pause, and the selected packet. `clearLog` and a new
  /// service have no list left to resume.
  private func resetLiveState() {
    pendingEntries.removeAll()
    isScrolledAway = false
    isUserPaused = false
    selectedEntry = nil
  }

  // MARK: - Subscription

  /// Listens while the view is visible. The task is stored before `loadEntries`
  /// returns, so a hide during that load cannot install a stream afterward.
  func subscribe() async {
    unsubscribe()

    guard let service = rxLogService else { return }

    if subscribedService !== service {
      entries.removeAll()
      groupCounts.removeAll()
      resetLiveState()
    }
    subscribedService = service

    let generation = subscribeGeneration
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      var resumed = false
      let resumeOnce = {
        guard !resumed else { return }
        resumed = true
        continuation.resume()
      }
      let task = Task { @MainActor [weak self] in
        guard let self else {
          resumeOnce()
          return
        }
        let loaded = await self.loadEntries(from: service)
        guard !Task.isCancelled, generation == self.subscribeGeneration else {
          resumeOnce()
          return
        }
        self.installLoadedEntries(loaded)
        let stream = service.entryStream()
        resumeOnce()
        for await entry in stream {
          guard !Task.isCancelled, generation == self.subscribeGeneration else { break }
          self.appendEntry(entry)
        }
      }
      streamTask = task
    }
  }

  /// Stop listening to updates.
  func unsubscribe() {
    subscribeGeneration &+= 1
    streamTask?.cancel()
    streamTask = nil
  }

  /// While paused, the reader's list stays put and rows persisted since it was
  /// built join the held arrivals instead.
  private func installLoadedEntries(_ loaded: [RxLogEntryDTO]) {
    guard isPaused else {
      entries = loaded
      pendingEntries.removeAll()
      rebuildGroupCounts()
      return
    }
    let knownIDs = Set(entries.map(\.id)).union(pendingEntries.map(\.id))
    for entry in loaded.reversed() where !knownIDs.contains(entry.id) {
      appendPending(entry)
    }
  }

  private func loadEntries(from service: RxLogService) async -> [RxLogEntryDTO] {
    #if DEBUG
      if let loadExistingEntriesForTesting {
        return await loadExistingEntriesForTesting()
      }
    #endif
    return await service.loadExistingEntries()
  }

  /// Clear all log entries.
  func clearLog() async {
    await rxLogService?.clearEntries()
    entries.removeAll()
    groupCounts.removeAll()
    resetLiveState()
  }

  // MARK: - Incremental Updates

  private func appendEntry(_ entry: RxLogEntryDTO) {
    guard !isPaused else {
      appendPending(entry)
      return
    }

    // Insert at front to maintain newest-first order (matching DB fetch sort)
    entries.insert(entry, at: 0)
    groupCounts[entry.packetHash, default: 0] += 1

    var removedMatchingSelected = false
    // Prune oldest (now at end) if over cap
    if entries.count > Self.maxEntries {
      let removed = entries.removeLast()
      groupCounts[removed.packetHash, default: 1] -= 1
      if groupCounts[removed.packetHash] == 0 {
        groupCounts.removeValue(forKey: removed.packetHash)
      }
      removedMatchingSelected = removed.packetHash == selectedEntry?.packetHash
    }

    if let selectedHash = selectedEntry?.packetHash,
       entry.packetHash == selectedHash || removedMatchingSelected {
      syncSelectedEntryArrivals()
    }
  }

  private func appendPending(_ entry: RxLogEntryDTO) {
    pendingEntries.append(entry)
    if pendingEntries.count > Self.maxEntries {
      pendingEntries.removeFirst()
    }
  }

  private func rebuildGroupCounts() {
    groupCounts = Dictionary(grouping: entries, by: \.packetHash)
      .mapValues(\.count)
    syncSelectedEntryArrivals()
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

  #if DEBUG
    var streamTaskForTesting: Task<Void, Never>? {
      streamTask
    }

    var loadExistingEntriesForTesting: (() async -> [RxLogEntryDTO])?
  #endif
}
