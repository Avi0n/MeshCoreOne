import MC1Services
import os.log
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "PathManagement")

/// Drives `navigationDestination(item:)`/`sheet(item:)` on the path editors.
/// Modeled as an enum (rather than `Bool` or `Void`) so the item binding has a
/// concrete `Identifiable`/`Hashable` type. Only `.append` exists today.
enum AddHopIntent: Hashable, Identifiable {
  case append

  var id: Self {
    self
  }
}

/// Sections the Add-Hop picker can narrow to. `.all` shows every section.
enum AddHopFilter: String, CaseIterable, Identifiable {
  case all, favorites, recent, discovered

  var id: String {
    rawValue
  }

  var localizedLabel: String {
    switch self {
    case .all: L10n.Contacts.Contacts.PathEdit.Filter.all
    case .favorites: L10n.Contacts.Contacts.PathEdit.Filter.favorites
    case .recent: L10n.Contacts.Contacts.PathEdit.Filter.recent
    case .discovered: L10n.Contacts.Contacts.PathEdit.Filter.discovered
    }
  }
}

/// Represents a single hop in the routing path with stable identity for SwiftUI
struct PathHop: Identifiable, Equatable {
  let id = UUID()
  var hashBytes: Data // Public key prefix bytes (1–3 bytes depending on hash mode)
  var publicKey: Data? // Full 32-byte key when known (for unambiguous matching)
  var resolvedName: String? // Contact name if resolved, nil if unknown

  var hashHex: String {
    hashBytes.uppercaseHexString()
  }

  var displayText: String {
    if let name = resolvedName {
      return "\(name) (\(hashHex))"
    }
    return hashHex
  }
}

/// Result of a path discovery operation
enum PathDiscoveryResult: Equatable {
  case success(hopCount: Int)
  case noPathFound
  case failed(String)

  var description: String {
    switch self {
    case let .success(hopCount):
      if hopCount == 0 {
        L10n.Contacts.Contacts.PathDiscovery.direct
      } else if hopCount == 1 {
        L10n.Contacts.Contacts.PathDiscovery.Hops.singular
      } else {
        L10n.Contacts.Contacts.PathDiscovery.Hops.plural(hopCount)
      }
    case .noPathFound:
      L10n.Contacts.Contacts.PathDiscovery.noResponse
    case let .failed(message):
      L10n.Contacts.Contacts.PathDiscovery.failed(message)
    }
  }
}

@Observable
@MainActor
final class PathManagementViewModel {
  // MARK: - State

  var isDiscovering = false
  var isSettingPath = false
  var discoveryResult: PathDiscoveryResult?
  var showDiscoveryResult = false
  var errorMessage: String?

  /// Path editing state
  var showingPathEditor = false
  /// Drives navigationDestination(item:) pushing the Add Hop picker.
  var insertionIntent: AddHopIntent?
  var editablePath: [PathHop] = [] // Current path being edited (stable identifiers)

  /// LRU of recently inserted public keys, most-recent first, capped at 8.
  /// Radio-scoped: cleared and reloaded when `loadContacts(radioID:)` fires.
  var recentPublicKeys: [Data] = []

  /// Captured radio scope for persistence. Set by `loadRecentKeys(for:)`.
  private var currentRadioID: UUID?

  /// Firmware `outPath` budget: 64 bytes. `encodePathLen` also clamps the
  /// hop-count field to 6 bits (0…63). The effective cap is whichever is
  /// smaller.
  private static let pathByteBudget = 64
  private static let pathHopFieldCap = 63

  var availableRepeaters: [ContactDTO] = [] // Known repeaters to add
  var availableRooms: [ContactDTO] = [] // Known rooms (may act as repeaters)
  var allContacts: [ContactDTO] = [] // All contacts for name resolution
  var discoveredNodes: [DiscoveredNodeDTO] = []

  /// Combined repeaters and rooms for resolution
  var availableNodes: [ContactDTO] {
    availableRepeaters + availableRooms
  }

  /// Discovered repeaters available to add
  var discoveredRepeaters: [DiscoveredNodeDTO] {
    discoveredNodes.filter { $0.nodeType == .repeater }
  }

  /// Discovery cancellation
  private var discoveryTask: Task<Void, Never>?

  // Discovery countdown state
  var discoverySecondsRemaining: Int?
  private var countdownTask: Task<Void, Never>?
  private var discoveryStartTime: Date?
  private var discoveryTimeoutSeconds: Double?

  // MARK: - Dependencies

  // Provider closures are re-evaluated at every use so a disconnect (or a
  // container rebuild) between actions is observed live, never a stale snapshot.
  private var dataStoreProvider: @MainActor () -> PersistenceStore? = { nil }
  private var contactServiceProvider: @MainActor () -> ContactService? = { nil }
  private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  private let recents: RecentHopsStore

  init(defaults: UserDefaults = .standard) {
    recents = RecentHopsStore(defaults: defaults)
  }

  /// Current hash size from device configuration (1, 2, or 3 bytes per hop).
  /// Clamped to `1...3` so a firmware `pathHashMode` of 3 (reserved) never trips
  /// `encodePathLen`'s `1...3` precondition on save.
  var hashSize: Int {
    let raw = connectedDeviceProvider()?.hashSize ?? 1
    return min(max(raw, 1), 3)
  }

  /// Maximum number of hops the path can hold under the current `hashSize`.
  /// Bounded by both the 6-bit hop-count field (0…63) and the 64-byte path
  /// payload. Past this cap, firmware silently truncates.
  var maxHopCount: Int {
    min(Self.pathHopFieldCap, Self.pathByteBudget / hashSize)
  }

  /// True when `editablePath.count` has hit `maxHopCount`. Callers (the
  /// Add Hop CTA + picker) disable insert affordances when this is true.
  var isPathFull: Bool {
    editablePath.count >= maxHopCount
  }

  // MARK: - Callbacks

  /// Called when path discovery completes and contact should be refreshed
  var onContactNeedsRefresh: (() -> Void)?

  // MARK: - Configuration

  /// Each provider is read live at its point of use; a provider returning
  /// `nil` mirrors a disconnected state, so unconfigured calls are no-ops.
  func configure(
    dataStore: @escaping @MainActor () -> PersistenceStore?,
    contactService: @escaping @MainActor () -> ContactService?,
    connectedDevice: @escaping @MainActor () -> DeviceDTO?,
    onContactNeedsRefresh: @escaping () -> Void
  ) {
    dataStoreProvider = dataStore
    contactServiceProvider = contactService
    connectedDeviceProvider = connectedDevice
    self.onContactNeedsRefresh = onContactNeedsRefresh
  }

  // MARK: - Name Resolution

  /// Resolve path hash bytes to a contact name if possible
  /// Returns the contact name if exactly one contact matches, otherwise falls back to discovered nodes
  func resolveHashToName(_ hashBytes: Data) -> String? {
    let matches = allContacts.filter { $0.publicKey.prefix(hashBytes.count) == hashBytes }
    if matches.count == 1 {
      return matches[0].resolvableName
    }
    // Fall back to discovered nodes with same single-match rule
    if matches.isEmpty {
      let discoveredMatches = discoveredNodes.filter { $0.publicKey.prefix(hashBytes.count) == hashBytes }
      if discoveredMatches.count == 1 {
        return discoveredMatches[0].resolvableName
      }
    }
    return nil // Ambiguous (multiple matches) or unknown
  }

  /// Resolve path hash bytes to a node's full 32-byte public key if exactly one
  /// match exists. Populating this on hops loaded from storage lets
  /// `saveEditedPath` re-encode the path at the device's current `hashSize`
  /// even if the stored path used a smaller size.
  func resolveHashToPublicKey(_ hashBytes: Data) -> Data? {
    let matches = allContacts.filter { $0.publicKey.prefix(hashBytes.count) == hashBytes }
    if matches.count == 1 {
      return matches[0].publicKey
    }
    if matches.isEmpty {
      let discoveredMatches = discoveredNodes.filter { $0.publicKey.prefix(hashBytes.count) == hashBytes }
      if discoveredMatches.count == 1 {
        return discoveredMatches[0].publicKey
      }
    }
    return nil
  }

  /// Create a PathHop from hash bytes, resolving the name and full public key
  /// when exactly one contact or discovered node matches the prefix.
  func createPathHop(from hashBytes: Data) -> PathHop {
    PathHop(
      hashBytes: hashBytes,
      publicKey: resolveHashToPublicKey(hashBytes),
      resolvedName: resolveHashToName(hashBytes)
    )
  }

  /// Load all contacts for name resolution and filter repeaters for adding.
  /// Always refreshes recents for the given `radioID` so switching radios on
  /// a reused view model picks up the right scope even if the contacts cache
  /// lets us skip the network fetch.
  func loadContacts(radioID: UUID, forceReload: Bool = false) async {
    guard let dataStore = dataStoreProvider() else { return }

    loadRecentKeys(for: radioID)

    // Skip if already loaded
    if !forceReload, !allContacts.isEmpty {
      return
    }

    do {
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
      allContacts = contacts
      availableRepeaters = contacts.filter { $0.type == .repeater }
      availableRooms = contacts.filter { $0.type == .room }
      let nodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
      discoveredNodes = nodes
    } catch {
      allContacts = []
      availableRepeaters = []
      availableRooms = []
      discoveredNodes = []
    }
  }

  /// Initialize editable path from contact's current path with name resolution.
  /// Resets `insertionIntent` so a stale value from a prior sheet presentation
  /// can't auto-push the Add Hop picker on open.
  ///
  /// Hops are normalized to the device's current ``hashSize`` so the editor
  /// never shows mixed-width hops after a `pathHashMode` change between edits.
  /// Resolvable hops re-slice from their full `publicKey`; unresolvable wider
  /// hops narrow via truncation; unresolvable narrower hops are left as-is so
  /// ``saveRejection`` can flag them on save.
  func initializeEditablePath(from contact: ContactDTO) {
    insertionIntent = nil
    let byteLength = contact.pathByteLength
    let storedHashSize = contact.pathHashSize
    let pathData = contact.outPath.prefix(byteLength)
    let targetHashSize = hashSize
    editablePath = stride(from: 0, to: pathData.count, by: storedHashSize).map { start in
      let end = min(start + storedHashSize, pathData.count)
      let bytes = Data(pathData[start..<end])
      let hop = createPathHop(from: bytes)
      return Self.normalizeHop(hop, targetHashSize: targetHashSize)
    }
  }

  /// Normalize a hop loaded from storage so its `hashBytes` width matches the
  /// device's current hash size. Three branches:
  ///
  /// 1. **Resolved** → re-slice from the full `publicKey` at the target width.
  /// 2. **Unresolved, wider than target** → truncate stored `hashBytes`.
  /// 3. **Unresolved, same width or narrower than target** → leave as-is. The
  ///    equal-width case is already consistent, and the narrower case has to
  ///    wait for ``saveRejection`` to block the save since we can't safely
  ///    widen an unknown prefix.
  ///
  /// - Precondition: `targetHashSize` is 1, 2, or 3.
  nonisolated static func normalizeHop(_ hop: PathHop, targetHashSize: Int) -> PathHop {
    precondition(1...3 ~= targetHashSize, "targetHashSize must be 1, 2, or 3")
    if let publicKey = hop.publicKey {
      return PathHop(
        hashBytes: Data(publicKey.prefix(targetHashSize)),
        publicKey: publicKey,
        resolvedName: hop.resolvedName
      )
    }
    if hop.hashBytes.count > targetHashSize {
      return PathHop(
        hashBytes: Data(hop.hashBytes.prefix(targetHashSize)),
        publicKey: nil,
        resolvedName: hop.resolvedName
      )
    }
    return hop
  }

  /// Append a node to the path. No-op when `isPathFull`. Records the pubkey
  /// in recents. The picker stays on screen so users can add several hops in
  /// a row; `insertionIntent` is cleared only when the user taps back
  /// (SwiftUI writes `nil` through the navigation binding).
  func insert(_ node: some RepeaterResolvable, at intent: AddHopIntent) {
    guard !isPathFull else { return }
    let hashBytes = Data(node.publicKey.prefix(hashSize))
    let hop = PathHop(
      hashBytes: hashBytes,
      publicKey: node.publicKey,
      resolvedName: node.resolvableName
    )
    switch intent {
    case .append:
      editablePath.append(hop)
    }
    recordRecent(node.publicKey)
  }

  // MARK: - Recents persistence

  /// Internal — tests invoke this directly via `@testable import MC1` to seed
  /// a specific radio scope without building a full `AppState`.
  func loadRecentKeys(for radioID: UUID) {
    currentRadioID = radioID
    recentPublicKeys = recents.load(for: radioID)
  }

  /// LRU insert via ``RecentHopsStore``, persisting under the captured radio scope.
  func recordRecent(_ publicKey: Data) {
    guard let radioID = currentRadioID else { return }
    recentPublicKeys = recents.record(publicKey, into: recentPublicKeys, for: radioID)
  }

  /// Remove a repeater from the path at index
  func removeRepeater(at index: Int) {
    guard editablePath.indices.contains(index) else { return }
    editablePath.remove(at: index)
  }

  /// Move a repeater within the path
  func moveRepeater(from source: IndexSet, to destination: Int) {
    editablePath.move(fromOffsets: source, toOffset: destination)
  }

  /// Reason a candidate edit cannot be saved, or `nil` if encoding is safe.
  /// Pure so tests can drive every branch without an `AppState`.
  ///
  /// - Precondition: `targetHashSize` is 1, 2, or 3. Matches `encodePathLen`'s
  ///   contract so bypassing the production call site (which clamps via
  ///   `hashSize`) still trips a clear assertion rather than a deep crash.
  nonisolated static func saveRejection(
    for hops: [PathHop],
    targetHashSize: Int,
    maxHopCount: Int
  ) -> String? {
    precondition(1...3 ~= targetHashSize, "targetHashSize must be 1, 2, or 3")
    // `Data.prefix(n)` returns `min(count, n)` bytes — no padding. A hop
    // whose stored `hashBytes` are narrower than `targetHashSize` and has no
    // full `publicKey` to widen from would serialise short, and firmware
    // would mis-parse the path against the declared `pathLength`.
    let hopNeedsResize = hops.contains { hop in
      hop.publicKey == nil && hop.hashBytes.count < targetHashSize
    }
    if hopNeedsResize {
      return L10n.Contacts.Contacts.PathManagement.Error.hopResizeRequired
    }
    if hops.count > maxHopCount {
      return L10n.Contacts.Contacts.PathManagement.Error.tooManyHops(maxHopCount)
    }
    return nil
  }

  /// Encode the path payload at the device's current hash size. Prefers the
  /// full 32-byte public key when available; falls back to stored
  /// `hashBytes`. Callers must have called `saveRejection` first to ensure
  /// the fallback never truncates below `targetHashSize`.
  ///
  /// - Precondition: `targetHashSize` is 1, 2, or 3.
  nonisolated static func encodeEditablePath(
    _ hops: [PathHop],
    targetHashSize: Int
  ) -> (path: Data, length: UInt8) {
    precondition(1...3 ~= targetHashSize, "targetHashSize must be 1, 2, or 3")
    let pathData = Data(hops.flatMap { hop -> Data in
      if let publicKey = hop.publicKey {
        return publicKey.prefix(targetHashSize)
      }
      return hop.hashBytes.prefix(targetHashSize)
    })
    let pathLength = encodePathLen(hashSize: targetHashSize, hopCount: hops.count)
    return (pathData, pathLength)
  }

  /// Save the edited path to the contact. Hops may have been loaded from a
  /// stored path with a smaller `hashSize` than the device currently reports
  /// (user changed `pathHashMode`). `saveRejection` catches the cases where
  /// we can't safely re-encode; otherwise `encodeEditablePath` re-encodes
  /// every hop at the current `hashSize`.
  func saveEditedPath(for contact: ContactDTO) async {
    guard let contactService = contactServiceProvider() else { return }

    errorMessage = nil
    let targetHashSize = hashSize

    if let rejection = Self.saveRejection(
      for: editablePath,
      targetHashSize: targetHashSize,
      maxHopCount: maxHopCount
    ) {
      errorMessage = rejection
      return
    }

    isSettingPath = true

    do {
      let encoded = Self.encodeEditablePath(editablePath, targetHashSize: targetHashSize)
      try await contactService.setPath(
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        path: encoded.path,
        pathLength: encoded.length
      )
      onContactNeedsRefresh?()
    } catch {
      errorMessage = L10n.Contacts.Contacts.PathManagement.Error.saveFailed(error.userFacingMessage)
    }

    isSettingPath = false
  }

  // MARK: - Path Operations

  /// Initiate path discovery for a contact (with cancel support).
  /// Reports `.noPathFound` if the remote node doesn't respond before the
  /// firmware-suggested timeout elapses.
  func discoverPath(for contact: ContactDTO) async {
    guard let contactService = contactServiceProvider() else { return }

    // Cancel any existing discovery
    discoveryTask?.cancel()

    isDiscovering = true
    discoveryResult = nil
    errorMessage = nil

    discoveryTask = Task { @MainActor in
      do {
        let sentResponse = try await contactService.sendPathDiscovery(
          radioID: contact.radioID,
          publicKey: contact.publicKey
        )

        let timeoutSeconds = FirmwareSuggestedTimeout.sanitizedSeconds(
          suggestedTimeoutMs: sentResponse.suggestedTimeoutMs,
          profile: .flood
        )
        if sentResponse.suggestedTimeoutMs == 0 {
          logger.warning(
            "Path discovery timeout default applied: firmware suggested no timeout, using \(timeoutSeconds)s"
          )
        } else {
          logger.info("Path discovery timeout: \(timeoutSeconds)s (firmware suggested: \(sentResponse.suggestedTimeoutMs)ms)")
        }

        // Start countdown timer
        self.discoveryTimeoutSeconds = timeoutSeconds
        self.discoveryStartTime = Date.now
        self.discoverySecondsRemaining = Int(timeoutSeconds)
        self.startCountdownTask()

        // Wait for push notification with firmware-suggested timeout.
        // AdvertisementService handler calls handleDiscoveryResponse()
        // which cancels this task early if a response arrives; sleep throws.
        try await Task.sleep(for: .seconds(timeoutSeconds))

        // Timeout: the remote node did not respond
        discoveryResult = .noPathFound
        showDiscoveryResult = true
      } catch is CancellationError {
        // Whoever cancelled us (user cancel, push-response handler, or a
        // fresh discoverPath re-entry) already resolved state. Bail so
        // this task's tail doesn't clobber the newer run's state.
        return
      } catch {
        discoveryResult = .failed(error.userFacingMessage)
        showDiscoveryResult = true
      }

      isDiscovering = false
      cleanupCountdownState()
    }
  }

  /// Start the countdown task that updates remaining seconds every 5 seconds.
  /// Cancels any prior countdown first so a `discoverPath` re-entry can't
  /// leave an orphan task writing `discoverySecondsRemaining` alongside the
  /// new one — the outer `discoveryTask` cancel doesn't propagate to this
  /// sibling.
  private func startCountdownTask() {
    countdownTask?.cancel()
    countdownTask = Task {
      while !Task.isCancelled, let timeout = discoveryTimeoutSeconds, let startTime = discoveryStartTime {
        do {
          try await Task.sleep(for: .seconds(5))
        } catch {
          break
        }
        // Re-check after sleep: cancellation can fire between iterations
        // without sleep throwing. Without this, a stale task could write
        // a stale remaining value over a fresh discovery's state.
        guard !Task.isCancelled else { break }
        let elapsed = Date.now.timeIntervalSince(startTime)
        let remaining = max(0, Int(timeout - elapsed))
        discoverySecondsRemaining = remaining
      }
    }
  }

  /// Clean up countdown state when discovery ends
  private func cleanupCountdownState() {
    countdownTask?.cancel()
    countdownTask = nil
    discoverySecondsRemaining = nil
    discoveryStartTime = nil
    discoveryTimeoutSeconds = nil
  }

  /// Cancel an in-progress path discovery
  func cancelDiscovery() {
    discoveryTask?.cancel()
    discoveryTask = nil
    isDiscovering = false
    cleanupCountdownState()
  }

  /// Called when a path discovery response is received via push notification.
  ///
  /// `hopCount` is `nil` when the wire's `out_path_len` byte used the reserved
  /// hash-size mode and couldn't be decoded. Treat that as "no valid path
  /// returned" rather than silently reporting a direct route.
  func handleDiscoveryResponse(hopCount: Int?) {
    discoveryTask?.cancel()
    isDiscovering = false
    cleanupCountdownState()

    if let hopCount {
      discoveryResult = .success(hopCount: hopCount)
    } else {
      discoveryResult = .noPathFound
    }
    showDiscoveryResult = true

    // Signal that contact data should be refreshed to show new path
    onContactNeedsRefresh?()
  }

  /// Reset the path for a contact (force flood routing)
  func resetPath(for contact: ContactDTO) async {
    guard let contactService = contactServiceProvider() else { return }

    isSettingPath = true
    errorMessage = nil

    do {
      try await contactService.resetPath(
        radioID: contact.radioID,
        publicKey: contact.publicKey
      )
      onContactNeedsRefresh?()
    } catch {
      errorMessage = L10n.Contacts.Contacts.PathManagement.Error.resetFailed(error.userFacingMessage)
    }

    isSettingPath = false
  }

  /// Set a specific path for a contact
  func setPath(for contact: ContactDTO, path: Data, pathLength: UInt8) async {
    guard let contactService = contactServiceProvider() else { return }

    isSettingPath = true
    errorMessage = nil

    do {
      try await contactService.setPath(
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        path: path,
        pathLength: pathLength
      )
      onContactNeedsRefresh?()
    } catch {
      errorMessage = L10n.Contacts.Contacts.PathManagement.Error.setFailed(error.userFacingMessage)
    }

    isSettingPath = false
  }
}

// MARK: - HopPickerSource

extension PathManagementViewModel: HopPickerSource {
  var currentHopCount: Int {
    editablePath.count
  }

  /// Contact paths are capped; expose the cap so the shared picker can show the
  /// max-hops state and stop bulk-add cleanly.
  var hopLimit: Int? {
    maxHopCount
  }

  func appendHop(_ node: some RepeaterResolvable) {
    insert(node, at: .append)
  }

  func classifyCodes(_ input: String) -> [HopCodeClassification] {
    let existing = Set(editablePath.map(\.hashBytes))
    let remaining = max(0, maxHopCount - editablePath.count)
    return HopCodeParser.classify(
      input: input,
      hashSize: hashSize,
      existingHashes: existing,
      remainingCapacity: remaining
    ) { hash in
      guard let publicKey = resolveHashToPublicKey(hash) else { return nil }
      return (publicKey, resolveHashToName(hash))
    }
  }

  /// Bulk-add resolvable codes, honoring the hop cap. Codes past the cap are
  /// classified `.pathFull` and skipped, so the path stops cleanly at `maxHopCount`.
  @discardableResult
  func addCodes(_ input: String) -> CodeInputResult {
    var result = CodeInputResult()
    for entry in classifyCodes(input) {
      switch entry.status {
      case let .willAdd(hop):
        editablePath.append(hop)
        if let publicKey = hop.publicKey { recordRecent(publicKey) }
        result.added.append(entry.code)
      case .alreadyInPath: result.alreadyInPath.append(entry.code)
      case .notFound: result.notFound.append(entry.code)
      case .invalidFormat: result.invalidFormat.append(entry.code)
      case .pathFull: break
      }
    }
    return result
  }
}
