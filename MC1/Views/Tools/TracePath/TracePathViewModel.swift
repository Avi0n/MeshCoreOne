import CoreLocation
import MC1Services
import MeshCore
import os.log
import SwiftUI
import UIKit

private let logger = Logger(subsystem: "com.mc1", category: "TracePath")

@Observable
@MainActor
final class TracePathViewModel {
  // MARK: - Path Building State

  var outboundPath: [PathHop] = []
  var availableRepeaters: [ContactDTO] = []
  var availableRooms: [ContactDTO] = []
  var autoReturnPath = true
  private var allContacts: [ContactDTO] = []
  var discoveredRepeaters: [DiscoveredNodeDTO] = []

  /// Recently added hop public keys, newest first. Source for the shared
  /// picker's "Recent" section; persisted per radio via ``RecentHopsStore``.
  var recentPublicKeys: [Data] = []
  private let recents: RecentHopsStore
  private var currentRadioID: UUID?

  init(defaults: UserDefaults = .standard) {
    recents = RecentHopsStore(defaults: defaults)
  }

  /// Combined repeaters and rooms for resolution (hex codes, map pins, etc.)
  var availableNodes: [ContactDTO] {
    availableRepeaters + availableRooms
  }

  // MARK: - Execution State

  var isRunning = false
  var result: TraceResult?
  var resultID: UUID? // Set to new UUID only on successful trace
  var errorMessage: String?
  var errorAutoClearDelay: Duration = .seconds(4)
  private var errorAutoClearTask: Task<Void, Never>?
  var errorHapticTrigger = 0 // Incremented on each error for haptic feedback

  /// Buffer between consecutive batch traces to avoid network flooding.
  private static let interTraceBufferMs = 500

  // MARK: - Batch Trace State

  var batchEnabled = false {
    didSet {
      if !batchEnabled {
        clearBatchState()
      }
    }
  }

  var batchSize = 3
  var currentTraceIndex = 0
  var completedResults: [TraceResult] = []

  /// Flag to signal batch loop should stop (since we await in the calling context)
  private var batchCancelled = false

  /// Continuation for awaiting trace response in batch mode
  private var traceContinuation: CheckedContinuation<Void, Never>?

  var isBatchInProgress: Bool {
    batchEnabled && currentTraceIndex > 0 && currentTraceIndex <= batchSize
  }

  var isBatchComplete: Bool {
    batchEnabled && completedResults.count == batchSize
  }

  var successfulResults: [TraceResult] {
    completedResults.filter(\.success)
  }

  var successCount: Int {
    successfulResults.count
  }

  /// Clear batch execution state
  func clearBatchState() {
    currentTraceIndex = 0
    completedResults = []
  }

  // MARK: - Batch Aggregates

  var averageRTT: Int? {
    let rtts = successfulResults.map(\.durationMs)
    guard !rtts.isEmpty else { return nil }
    return rtts.reduce(0, +) / rtts.count
  }

  var minRTT: Int? {
    successfulResults.map(\.durationMs).min()
  }

  var maxRTT: Int? {
    successfulResults.map(\.durationMs).max()
  }

  /// Returns aggregate stats for a hop at the given index (0 = start node, 1+ = intermediate/end)
  /// Returns nil for start node (index 0) as it has no received SNR
  func hopStats(at index: Int) -> (avg: Double, min: Double, max: Double)? {
    guard index > 0 else { return nil } // Start node has no SNR

    let snrValues = successfulResults.compactMap { result -> Double? in
      guard index < result.hops.count else { return nil }
      let hop = result.hops[index]
      guard !hop.isStartNode else { return nil }
      return hop.snr
    }

    guard !snrValues.isEmpty else { return nil }

    let avg = snrValues.reduce(0, +) / Double(snrValues.count)
    let min = snrValues.min() ?? 0
    let max = snrValues.max() ?? 0

    return (avg, min, max)
  }

  /// Returns the SNR for a hop from the most recent successful result
  func latestHopSNR(at index: Int) -> Double? {
    guard let latest = successfulResults.last,
          index < latest.hops.count else { return nil }
    return latest.hops[index].snr
  }

  // MARK: - Saved Path State

  var activeSavedPath: SavedTracePathDTO?
  var isRunningSavedPath: Bool {
    activeSavedPath != nil
  }

  /// Returns the second-most-recent successful run for comparison display
  var previousRun: TracePathRunDTO? {
    let successfulRuns = activeSavedPath?.runs
      .filter(\.success)
      .sorted(by: { $0.date > $1.date }) ?? []
    return successfulRuns.count >= 2 ? successfulRuns[1] : nil
  }

  // MARK: - Trace Correlation

  private var pendingTag: UInt32?
  private var pendingDeviceID: UUID? // Track which device initiated trace
  private var traceStartTime: Date?
  private var timeoutTask: Task<Void, Never>?
  private var executionTask: Task<Void, Never>?
  /// Owns one run. Hiding the tool does not bump it.
  private var executionEpoch = Epoch()
  private var waitingTicket: Epoch.Ticket?
  private var responseDeadline: Date?
  private var pendingIsBatch = false
  private var isWorkspaceVisible = false
  private var resultDismissedWhileVisible = false
  private var presentedSuccessID: UUID?

  // MARK: - Path Hash Tracking (for save validation)

  private var pendingPathHash: [UInt8]?
  // resultPathHash removed - now derived from result.tracedPathBytes

  // MARK: - Event Subscription

  private var traceEventsTask: Task<Void, Never>?
  private var listeningService: AdvertisementService?
  private var listenerAttached = false
  private var listenGeneration: UInt = 0

  /// Start listening for trace responses on the current connection's
  /// `AdvertisementService`. A second call for that same live subscription
  /// does nothing. While disconnected there is no service yet.
  func startListening() {
    guard let advertisementService else { return }
    if listenerAttached,
       listeningService === advertisementService,
       let traceEventsTask,
       !traceEventsTask.isCancelled {
      return
    }
    listenGeneration &+= 1
    let generation = listenGeneration
    let service = advertisementService
    listeningService = service
    listenerAttached = true
    traceEventsTask?.cancel()
    let events = service.events()
    traceEventsTask = Task { [weak self] in
      for await event in events {
        guard let self, generation == self.listenGeneration else { return }
        guard case let .traceResponse(traceInfo, radioID) = event else { continue }
        self.handleTraceResponse(traceInfo, radioID: radioID)
      }
      guard let self, generation == self.listenGeneration else { return }
      self.listenerAttached = false
      self.handleTraceStreamEnded(finished: service)
    }
  }

  /// Stop listening for trace responses
  func stopListening() {
    listenGeneration &+= 1
    listenerAttached = false
    listeningService = nil
    traceEventsTask?.cancel()
    traceEventsTask = nil
  }

  /// Records whether Trace Path is on screen. Hiding does not cancel a run.
  func setWorkspaceVisible(_ visible: Bool) {
    isWorkspaceVisible = visible
    if visible {
      startListening()
      return
    }
    errorAutoClearTask?.cancel()
    errorAutoClearTask = nil
    if pendingTag == nil, executionTask == nil {
      stopListening()
    }
  }

  /// Same-device ready. Attaches a waiting run to the service the provider
  /// returns now and does not bump the epoch.
  func reattachIfWaiting() {
    guard pendingTag != nil else { return }
    startListening()
  }

  /// Foreground return. A deadline that passed while `Task.sleep` was
  /// suspended ends the run through the same path as an in-process timeout.
  func expireWaitingRunIfDeadlinePassed() {
    guard let deadline = responseDeadline, Date() >= deadline, let tag = pendingTag else { return }
    applyTimeout(tag: tag)
  }

  func noteResultSheetDismissed() {
    if isWorkspaceVisible {
      resultDismissedWhileVisible = true
      if isBatchInProgress {
        cancelBatchTrace()
      }
      return
    }
    presentedSuccessID = nil
  }

  /// List mode asks once per stored success. A second call for the same
  /// `resultID` returns nil. Map mode does not call this.
  func consumeStoredResultForPresentation() -> TraceResult? {
    guard isWorkspaceVisible, let result, result.success, let resultID else { return nil }
    guard !resultDismissedWhileVisible, presentedSuccessID != resultID else { return nil }
    let batchStillSending = isBatchInProgress
    guard !isRunning || batchStillSending else { return nil }
    presentedSuccessID = resultID
    return result
  }

  /// Drops the in-memory run. Does not set `errorMessage` or append a failed run.
  func reset() {
    cancelExecution()
    stopListening()
    outboundPath.removeAll()
    result = nil
    resultID = nil
    completedResults.removeAll()
    clearBatchState()
    activeSavedPath = nil
    responseDeadline = nil
    presentedSuccessID = nil
    resultDismissedWhileVisible = false
    clearError()
  }

  // MARK: - Dependencies

  struct Dependencies {
    // Provider closures are re-evaluated at every use so a disconnect (or a
    // container rebuild) mid-trace is observed live, never a stale snapshot.
    var dataStore: @MainActor () -> PersistenceStore?
    var session: @MainActor () -> MeshCoreSession?
    var advertisementService: @MainActor () -> AdvertisementService?
    var connectedDevice: @MainActor () -> DeviceDTO?
    var bestAvailableLocation: @MainActor () -> CLLocation?
  }

  private var deps = Dependencies(
    dataStore: { nil },
    session: { nil },
    advertisementService: { nil },
    connectedDevice: { nil },
    bestAvailableLocation: { nil }
  )

  private var dataStore: PersistenceStore? {
    deps.dataStore()
  }

  private var session: MeshCoreSession? {
    deps.session()
  }

  private var advertisementService: AdvertisementService? {
    deps.advertisementService()
  }

  private var connectedDevice: DeviceDTO? {
    deps.connectedDevice()
  }

  private var bestAvailableLocation: CLLocation? {
    deps.bestAvailableLocation()
  }

  // MARK: - Computed Properties

  /// Full path data: outbound + optional mirrored return (minus last hop to avoid duplicate)
  var fullPathData: Data {
    let outbound = outboundPath.map(\.hashBytes)
    guard !outbound.isEmpty else { return Data() }

    if autoReturnPath {
      return Data((outbound + SavedPathCodec.mirroredReturn(of: outbound)).flatMap(\.self))
    } else {
      return Data(outbound.flatMap(\.self))
    }
  }

  /// Full path as byte array for backward compatibility
  var fullPathBytes: [UInt8] {
    Array(fullPathData)
  }

  /// Per-trace hash size override (path_sz code 0/1/2). `nil` follows the
  /// radio's configured `pathHashMode`. Honored by firmware v1.11+.
  var traceHashMode: UInt8?

  /// Path_sz code used for this trace's flags byte and hop widths: the
  /// override when set, otherwise the radio's configured `pathHashMode`.
  var effectiveTraceMode: UInt8 {
    traceHashMode ?? (connectedDevice?.pathHashMode ?? 0)
  }

  /// Trace hash size in bytes per hop (1, 2, or 4), derived from the effective
  /// mode. Trace uses power-of-2 encoding (`1 << mode`), unlike the linear
  /// 1/2/3-byte routing hash size.
  var hashSize: Int {
    1 << Int(effectiveTraceMode)
  }

  /// Comma-separated path string for display/copy, chunked by hash size
  var fullPathString: String {
    let data = fullPathData
    let size = outboundPath.first?.hashBytes.count ?? hashSize
    return stride(from: 0, to: data.count, by: size).map { start in
      let end = min(start + size, data.count)
      return data[start..<end].uppercaseHexString()
    }.joined(separator: ",")
  }

  /// Can run trace if path has at least one hop and not currently running
  var canRunTraceWhenConnected: Bool {
    !outboundPath.isEmpty && !isRunning
  }

  /// Can save path if result is successful and path hasn't changed since trace ran
  var canSavePath: Bool {
    if batchEnabled {
      guard !completedResults.isEmpty else { return false }
      guard let firstSuccess = successfulResults.first else { return false }
      return fullPathBytes == firstSuccess.tracedPathBytes
    } else {
      guard let result, result.success else { return false }
      return fullPathBytes == result.tracedPathBytes
    }
  }

  // MARK: - Distance Calculation

  /// Total path distance in meters, using a priority cascade:
  /// 1. Full path (including device legs) if device has location
  /// 2. Intermediate repeaters only if device lacks location
  /// 3. Nil if fewer than 2 hops with valid location
  var totalPathDistance: Double? {
    guard let result, result.success else { return nil }
    guard result.hops.count >= 2 else { return nil }

    // Priority 1: Full path including device legs
    if let fullDistance = calculateDistance(for: result.hops) {
      return fullDistance
    }

    // Priority 2: Intermediate repeaters only (device has no location)
    let repeaters = result.hops.filter { !$0.isStartNode && !$0.isEndNode }
    return calculateDistance(for: repeaters)
  }

  /// Calculate total distance for a sequence of hops, or nil if any lacks location
  private func calculateDistance(for hops: [TraceHop]) -> Double? {
    guard hops.count >= 2 else { return nil }

    var totalMeters: Double = 0

    for index in 0..<(hops.count - 1) {
      let current = hops[index]
      let next = hops[index + 1]

      guard current.hasLocation, next.hasLocation,
            let curLat = current.latitude, let curLon = current.longitude,
            let nextLat = next.latitude, let nextLon = next.longitude else {
        return nil
      }

      let from = CLLocation(latitude: curLat, longitude: curLon)
      let to = CLLocation(latitude: nextLat, longitude: nextLon)
      totalMeters += from.distance(from: to)
    }

    return totalMeters
  }

  /// Names of intermediate repeaters that lack location data
  var repeatersWithoutLocation: [String] {
    guard let result else { return [] }

    return result.hops
      .filter { !$0.isStartNode && !$0.isEndNode && !$0.hasLocation }
      .map { $0.resolvedName ?? $0.hashDisplayString ?? "Unknown" }
  }

  /// Whether the distance calculation used intermediate-only fallback (device has no location)
  var isDistanceUsingFallback: Bool {
    guard let result, result.success, totalPathDistance != nil else { return false }

    guard let startNode = result.hops.first, let endNode = result.hops.last else { return false }

    // If device nodes lack location, we used the intermediate-only fallback
    return !startNode.hasLocation || !endNode.hasLocation
  }

  // MARK: - Configuration

  /// Each provider is read live at its point of use; a provider returning
  /// `nil` mirrors a disconnected state, so unconfigured calls are no-ops.
  func configure(dependencies: Dependencies) {
    deps = dependencies
  }

  // MARK: - Error Handling

  func setError(_ message: String) {
    errorAutoClearTask?.cancel()
    errorAutoClearTask = nil

    errorMessage = message
    errorHapticTrigger += 1

    guard isWorkspaceVisible else { return }

    errorAutoClearTask = Task { @MainActor [weak self] in
      guard let self else { return }
      try? await Task.sleep(for: errorAutoClearDelay)
      guard !Task.isCancelled else { return }
      errorMessage = nil
    }
  }

  func clearError() {
    errorAutoClearTask?.cancel()
    errorAutoClearTask = nil
    errorMessage = nil
  }

  // MARK: - Hash Resolution

  /// Resolve hash bytes to the best matching node name (contacts first, then discovered)
  func resolveHashToName(_ hashBytes: Data) -> String? {
    resolveNode(for: hashBytes)?.resolvableName
  }

  /// Try contacts first, then discovered nodes. Returns the best match from either source.
  private func resolveNode(for hashBytes: Data) -> (any RepeaterResolvable)? {
    if let contact = RepeaterResolver.bestMatch(for: hashBytes, in: availableNodes, userLocation: bestAvailableLocation) {
      return contact
    }
    return RepeaterResolver.bestMatch(for: hashBytes, in: discoveredRepeaters, userLocation: bestAvailableLocation)
  }

  /// Try contacts first, then discovered nodes, using full PathHop for exact key match.
  private func resolveNode(for hop: PathHop) -> (any RepeaterResolvable)? {
    if let contact = RepeaterResolver.bestMatch(for: hop, in: availableNodes, userLocation: bestAvailableLocation) {
      return contact
    }
    return RepeaterResolver.bestMatch(for: hop, in: discoveredRepeaters, userLocation: bestAvailableLocation)
  }

  // MARK: - Data Loading

  /// Load contacts for name resolution and available repeaters
  func loadContacts(radioID: UUID) async {
    currentRadioID = radioID
    recentPublicKeys = recents.load(for: radioID)
    // Drop an override carried from a prior radio the current one can't
    // honor, so the trace falls back to its pathHashMode.
    if connectedDevice?.supportsTraceHashSizeOverride != true {
      traceHashMode = nil
    }
    guard let dataStore else { return }
    do {
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
      allContacts = contacts
      availableRepeaters = contacts.filter { $0.type == .repeater }
      availableRooms = contacts.filter { $0.type == .room }
      let nodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
      discoveredRepeaters = nodes.filter { $0.nodeType == .repeater }
    } catch {
      logger.error("Failed to load contacts: \(error.localizedDescription)")
      allContacts = []
      availableRepeaters = []
      availableRooms = []
      discoveredRepeaters = []
    }
  }

  // MARK: - Path Manipulation

  /// Add a node to the outbound path
  func addNode(_ node: some RepeaterResolvable) {
    clearError()
    let hashBytes = Data(node.publicKey.prefix(hashSize))
    let hop = PathHop(hashBytes: hashBytes, publicKey: node.publicKey, resolvedName: node.resolvableName)
    outboundPath.append(hop)
    activeSavedPath = nil
    pendingPathHash = nil
    result = nil
  }

  /// Apply a per-trace hash size override and rebuild every hop to a uniform
  /// width. Hops with a public key re-prefix exactly; key-less hops (from a
  /// saved path with an unresolved hop) have nothing to widen from and are
  /// zero-padded. The width must be uniform because the trace flags byte
  /// declares a single hash size for the whole path.
  func setTraceHashMode(_ mode: UInt8) {
    clearError()
    traceHashMode = mode
    let size = hashSize
    outboundPath = outboundPath.map { hop in
      var hop = hop
      let source = hop.publicKey ?? hop.hashBytes
      var bytes = Data(source.prefix(size))
      if bytes.count < size {
        bytes.append(Data(repeating: 0, count: size - bytes.count))
      }
      hop.hashBytes = bytes
      return hop
    }
    activeSavedPath = nil
    pendingPathHash = nil
    result = nil
  }

  /// Parse comma-separated hex codes and add matching repeaters to the path.
  /// Shares ``HopCodeParser`` with the bulk-add preview so the two never diverge.
  @discardableResult
  func addRepeatersFromCodes(_ input: String) -> CodeInputResult {
    var result = CodeInputResult()
    for entry in classifyCodes(input) {
      switch entry.status {
      case let .willAdd(hop):
        outboundPath.append(hop)
        if let publicKey = hop.publicKey { recordRecent(publicKey) }
        result.added.append(entry.code)
      case .alreadyInPath: result.alreadyInPath.append(entry.code)
      case .notFound: result.notFound.append(entry.code)
      case .invalidFormat: result.invalidFormat.append(entry.code)
      case .pathFull: assertionFailure("trace paths are uncapped, so classifyCodes never yields .pathFull")
      }
    }

    // Clear saved path reference if we added anything
    if !result.added.isEmpty {
      activeSavedPath = nil
      pendingPathHash = nil
      self.result = nil
      clearError()
    }

    return result
  }

  /// Remove a repeater from the path
  func removeRepeater(at index: Int) {
    clearError()
    guard outboundPath.indices.contains(index) else { return }
    outboundPath.remove(at: index)
    activeSavedPath = nil
    pendingPathHash = nil
    result = nil
  }

  /// Move a repeater within the path
  func moveRepeater(from source: IndexSet, to destination: Int) {
    clearError()
    outboundPath.move(fromOffsets: source, toOffset: destination)
    activeSavedPath = nil
    pendingPathHash = nil
    result = nil
  }

  /// Copy full path string to clipboard
  func copyPathToClipboard() {
    UIPasteboard.general.string = fullPathString
  }

  /// Generate a default name from the path (e.g., "Tower → ... → Ridge")
  func generatePathName() -> String {
    let names = outboundPath.compactMap(\.resolvedName)
    switch names.count {
    case 0:
      return L10n.Contacts.Contacts.PathName.prefix(String(fullPathString.prefix(8)))
    case 1:
      return names[0]
    case 2:
      return L10n.Contacts.Contacts.PathName.twoEndpoints(names[0], names[1])
    default:
      return L10n.Contacts.Contacts.PathName.multipleEndpoints(names[0], names[names.count - 1])
    }
  }

  /// Extract SNR values from intermediate hops (excluding start and end nodes)
  private func extractHopsSNR(from result: TraceResult) -> [Double] {
    result.hops
      .filter { !$0.isStartNode && !$0.isEndNode }
      .map(\.snr)
  }

  /// Save the current path with the given name
  /// - Returns: `true` if save succeeded, `false` otherwise
  @discardableResult
  func savePath(name: String) async -> Bool {
    guard let radioID = connectedDevice?.radioID,
          let dataStore else { return false }

    // For batch mode, save all completed results
    if batchEnabled, !completedResults.isEmpty {
      guard let firstSuccess = successfulResults.first else { return false }

      // Create initial run from first successful result
      let initialRun = TracePathRunDTO(
        id: UUID(),
        date: Date(),
        success: true,
        roundTripMs: firstSuccess.durationMs,
        hopsSNR: extractHopsSNR(from: firstSuccess)
      )

      do {
        let savedPath = try await dataStore.createSavedTracePath(
          radioID: radioID,
          name: name,
          pathBytes: Data(firstSuccess.tracedPathBytes),
          hashSize: hashSize,
          initialRun: initialRun
        )

        // Append remaining results as additional runs
        for (index, batchResult) in completedResults.enumerated() {
          // Skip the first successful result (already saved as initial)
          if batchResult.id == firstSuccess.id { continue }

          let run = TracePathRunDTO(
            id: UUID(),
            date: Date().addingTimeInterval(Double(index)),
            success: batchResult.success,
            roundTripMs: batchResult.durationMs,
            hopsSNR: extractHopsSNR(from: batchResult)
          )
          try await dataStore.appendTracePathRun(pathID: savedPath.id, run: run)
        }

        // Refresh to get all runs
        if let updated = try await dataStore.fetchSavedTracePath(id: savedPath.id) {
          activeSavedPath = updated
        }
        logger.info("Saved batch path: \(name) with \(self.completedResults.count) runs")
        return true
      } catch {
        logger.error("Failed to save batch path: \(error.localizedDescription)")
        return false
      }
    }

    // Single trace mode (original behavior)
    guard let result, result.success else { return false }

    let initialRun = TracePathRunDTO(
      id: UUID(),
      date: Date(),
      success: true,
      roundTripMs: result.durationMs,
      hopsSNR: extractHopsSNR(from: result)
    )

    do {
      let savedPath = try await dataStore.createSavedTracePath(
        radioID: radioID,
        name: name,
        pathBytes: Data(result.tracedPathBytes),
        hashSize: hashSize,
        initialRun: initialRun
      )
      activeSavedPath = savedPath
      logger.info("Saved path: \(name)")
      return true
    } catch {
      logger.error("Failed to save path: \(error.localizedDescription)")
      return false
    }
  }

  /// Shared with `fullPathData` so load inverts the same auto-return mirror.
  private enum SavedPathCodec {
    static func mirroredReturn(of outbound: [Data]) -> [Data] {
      Array(outbound.reversed().dropFirst())
    }

    static func hopHashes(from pathBytes: Data, hashSize: Int) -> [Data] {
      stride(from: 0, to: pathBytes.count, by: hashSize).map { start in
        let end = min(start + hashSize, pathBytes.count)
        return Data(pathBytes[start..<end])
      }
    }

    /// `nil` unless hops equal outbound plus `mirroredReturn`. Odd length is required (`2n-1`) but not sufficient.
    static func autoReturnOutboundCount(in hops: [Data]) -> Int? {
      guard !hops.count.isMultiple(of: 2) else { return nil }
      let outboundCount = (hops.count + 1) / 2
      let outbound = Array(hops.prefix(outboundCount))
      guard Array(hops.dropFirst(outboundCount)) == mirroredReturn(of: outbound) else {
        return nil
      }
      return outboundCount
    }
  }

  /// Load a saved path into the builder
  func loadSavedPath(_ savedPath: SavedTracePathDTO) {
    outboundPath.removeAll()
    result = nil
    pendingPathHash = nil

    let fullPath = savedPath.pathBytes
    let size = savedPath.hashSize
    guard !fullPath.isEmpty else { return }

    // Match the trace hash mode to the saved width (size is 1/2/4 -> code
    // 0/1/2), but only on a radio that honors the per-trace override;
    // otherwise the trace follows the configured pathHashMode.
    if connectedDevice?.supportsTraceHashSizeOverride == true {
      traceHashMode = UInt8(size.trailingZeroBitCount)
    } else {
      traceHashMode = nil
    }

    // Invert `fullPathData`: a mirrored palindrome is outbound plus auto-return;
    // any other blob is the full hop list with auto-return off.
    let hops = SavedPathCodec.hopHashes(from: fullPath, hashSize: size)
    let outboundCount: Int
    if let count = SavedPathCodec.autoReturnOutboundCount(in: hops) {
      autoReturnPath = true
      outboundCount = count
    } else {
      autoReturnPath = false
      outboundCount = hops.count
    }

    for hashBytes in hops.prefix(outboundCount) {
      let match = resolveNode(for: hashBytes)
      outboundPath.append(PathHop(
        hashBytes: hashBytes,
        publicKey: match?.publicKey,
        resolvedName: match?.resolvableName
      ))
    }

    activeSavedPath = savedPath
    logger.info("Loaded saved path: \(savedPath.name) with \(self.outboundPath.count) hops")
  }

  /// Clear the path (resets to empty state)
  func clearPath() {
    clearError()
    activeSavedPath = nil
    outboundPath.removeAll()
    result = nil
    pendingPathHash = nil
    traceHashMode = nil
  }

  /// Clear active saved path reference if it matches the deleted path
  func handleSavedPathDeleted(id: UUID) {
    guard activeSavedPath?.id == id else { return }
    activeSavedPath = nil
    logger.info("Cleared active saved path reference after deletion")
  }

  /// Find a saved path matching the current path bytes
  /// Returns the most recently used match if multiple exist
  private func findMatchingSavedPath() async -> SavedTracePathDTO? {
    guard let radioID = connectedDevice?.radioID,
          let dataStore else { return nil }

    let pathBytes = fullPathBytes
    guard !pathBytes.isEmpty else { return nil }

    do {
      let savedPaths = try await dataStore.fetchSavedTracePaths(radioID: radioID)
      let matches = savedPaths.filter { $0.pathHashBytes == pathBytes }

      // Return most recently used (by latest run date)
      return matches.max { path1, path2 in
        let date1 = path1.runs.map(\.date).max() ?? .distantPast
        let date2 = path2.runs.map(\.date).max() ?? .distantPast
        return date1 < date2
      }
    } catch {
      logger.error("Failed to fetch saved paths for matching: \(error.localizedDescription)")
      return nil
    }
  }

  // MARK: - Trace Execution

  /// Starts a single or batch trace owned by this model so List and Map share
  /// one cancellable operation.
  func startTrace() {
    cancelExecution()
    let ticket = executionEpoch.ticket()
    startListening()
    executionTask = Task { @MainActor [weak self] in
      guard let self else { return }
      if self.batchEnabled {
        await self.runBatchTrace(ticket: ticket)
      } else {
        await self.runTrace(ticket: ticket)
      }
      guard ticket.isCurrent(in: self.executionEpoch) else { return }
      self.executionTask = nil
    }
  }

  /// Execute the trace and wait for response
  func runTrace() async {
    await runTrace(ticket: executionEpoch.ticket())
  }

  private func runTrace(ticket: Epoch.Ticket) async {
    guard isCurrent(ticket), canSendTrace, !outboundPath.isEmpty else { return }

    timeoutTask?.cancel()
    timeoutTask = nil

    resultID = nil
    clearError()

    if activeSavedPath == nil {
      if let matchedPath = await findMatchingSavedPath() {
        guard isCurrent(ticket) else { return }
        activeSavedPath = matchedPath
        logger.info("Matched path to saved path: \(matchedPath.name)")
      }
    }
    guard isCurrent(ticket) else { return }

    isRunning = true
    result = nil
    pendingPathHash = fullPathBytes

    switch await sendAndArm(ticket: ticket, isBatch: false) {
    case .abandoned, .failedBeforeTag:
      return
    case .waiting:
      armCurrentWait(ticket: ticket)
    }
  }

  // MARK: - Batch Trace Execution

  /// Execute multiple traces in batch mode
  func runBatchTrace() async {
    await runBatchTrace(ticket: executionEpoch.ticket())
  }

  private func runBatchTrace(ticket: Epoch.Ticket) async {
    guard batchEnabled else {
      await runTrace(ticket: ticket)
      return
    }

    clearBatchState()
    batchCancelled = false
    resultID = nil
    clearError()

    guard isCurrent(ticket), canSendTrace, !outboundPath.isEmpty else { return }

    if activeSavedPath == nil {
      if let matchedPath = await findMatchingSavedPath() {
        guard isCurrent(ticket) else { return }
        activeSavedPath = matchedPath
        logger.info("Matched path to saved path: \(matchedPath.name)")
      }
    }
    guard isCurrent(ticket) else { return }

    isRunning = true
    result = nil

    for traceIndex in 1...batchSize {
      guard isCurrent(ticket), !batchCancelled else { break }

      currentTraceIndex = traceIndex
      await executeSingleTrace(ticket: ticket)

      if let latestResult = completedResults.last, latestResult.success {
        if successCount == 1 {
          result = latestResult
          resultID = UUID()
          resultDismissedWhileVisible = false
          presentedSuccessID = nil
        } else {
          result = latestResult
        }
      }

      if traceIndex < batchSize {
        guard isCurrent(ticket), !batchCancelled else { break }
        try? await Task.sleep(for: .milliseconds(Self.interTraceBufferMs))
        guard isCurrent(ticket), !batchCancelled else { break }
      }
    }

    guard isCurrent(ticket) else { return }

    isRunning = false
    currentTraceIndex = 0

    if isBatchComplete, successCount == 0 {
      setError(L10n.Contacts.Contacts.Trace.Error.allFailed(batchSize))
    }
  }

  /// Execute a single trace within a batch, storing result in completedResults
  private func executeSingleTrace(ticket: Epoch.Ticket) async {
    guard isCurrent(ticket) else { return }
    pendingPathHash = fullPathBytes

    switch await sendAndArm(ticket: ticket, isBatch: true) {
    case .abandoned, .failedBeforeTag:
      return
    case .waiting:
      break
    }

    guard pendingTag != nil, isCurrent(ticket) else { return }
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      traceContinuation = continuation
      armCurrentWait(ticket: ticket)
    }
  }

  private enum SendArmResult {
    case waiting
    case failedBeforeTag
    case abandoned
  }

  /// Assigns `pendingTag` only after the pre-tag step. A throw after that
  /// leaves the tag in place and arms the deadline if it is not armed yet.
  private func sendAndArm(ticket: Epoch.Ticket, isBatch: Bool) async -> SendArmResult {
    do {
      try await preflightBeforeTag()
    } catch {
      guard isCurrent(ticket) else { return .abandoned }
      failBeforeTag(ticket: ticket, isBatch: isBatch)
      return .failedBeforeTag
    }

    let tag = UInt32.random(in: 0...UInt32.max)
    pendingTag = tag
    waitingTicket = ticket
    pendingIsBatch = isBatch
    pendingDeviceID = connectedDevice?.radioID
    traceStartTime = Date()

    do {
      let sentInfo = try await performSendTrace(tag: tag, flags: effectiveTraceMode, path: Data(fullPathBytes))
      guard isCurrent(ticket) else { return .abandoned }
      let timeoutSeconds = responseTimeoutSeconds(suggestedTimeoutMs: sentInfo.suggestedTimeoutMs)
      logger.info("Sent trace with tag \(tag), path: \(self.fullPathString), timeout: \(timeoutSeconds)s")
      pendingTimeoutSeconds = timeoutSeconds
      return .waiting
    } catch {
      guard isCurrent(ticket) else { return .abandoned }
      if pendingTag == tag {
        if responseDeadline == nil {
          pendingTimeoutSeconds = unackedTimeoutSeconds
        }
        return .waiting
      }
      failBeforeTag(ticket: ticket, isBatch: isBatch)
      return .failedBeforeTag
    }
  }

  private func preflightBeforeTag() async throws {
    #if DEBUG
      if let throwBeforeTagForTesting {
        try await throwBeforeTagForTesting()
      }
    #endif
  }

  private func failBeforeTag(ticket: Epoch.Ticket, isBatch: Bool) {
    guard isCurrent(ticket) else { return }
    logger.error("Failed to send trace before a tag was assigned")
    if isBatch {
      let failedResult = TraceResult.sendFailed(
        L10n.Contacts.Contacts.Trace.Error.sendFailed,
        attemptedPath: pendingPathHash ?? [],
        hashSize: hashSize
      )
      completedResults.append(failedResult)
    } else {
      setError(L10n.Contacts.Contacts.Trace.Error.sendFailed)
      isRunning = false
    }
    recordFailedRun(ticket: ticket)
    pendingPathHash = nil
    pendingTag = nil
    pendingDeviceID = nil
    waitingTicket = nil
  }

  private var canSendTrace: Bool {
    #if DEBUG
      if sendTraceForTesting != nil { return true }
    #endif
    return session != nil
  }

  private func isCurrent(_ ticket: Epoch.Ticket) -> Bool {
    ticket.isCurrent(in: executionEpoch) && !Task.isCancelled
  }

  private func responseTimeoutSeconds(suggestedTimeoutMs: UInt32) -> Double {
    #if DEBUG
      if let responseTimeoutSecondsForTesting {
        return responseTimeoutSecondsForTesting
      }
    #endif
    return FirmwareSuggestedTimeout.sanitizedSeconds(
      suggestedTimeoutMs: suggestedTimeoutMs,
      profile: .flood
    )
  }

  private var unackedTimeoutSeconds: Double {
    #if DEBUG
      if let unackedTimeoutSecondsForTesting {
        return unackedTimeoutSecondsForTesting
      }
    #endif
    return FirmwareSuggestedTimeout.Profile.flood.defaultSeconds
  }

  private func performSendTrace(tag: UInt32, flags: UInt8, path: Data) async throws -> MessageSentInfo {
    #if DEBUG
      if let sendTraceForTesting {
        return try await sendTraceForTesting(tag, flags, path)
      }
    #endif
    guard let session else {
      throw CancellationError()
    }
    return try await session.sendTrace(tag: tag, authCode: 0, flags: flags, path: path)
  }

  private var pendingTimeoutSeconds: Double = 0

  private func armCurrentWait(ticket: Epoch.Ticket) {
    guard isCurrent(ticket), let tag = pendingTag, responseDeadline == nil else { return }
    armTimeout(tag: tag, timeoutSeconds: pendingTimeoutSeconds)
  }

  private func armTimeout(tag: UInt32, timeoutSeconds: Double) {
    responseDeadline = Date().addingTimeInterval(timeoutSeconds)
    let deadline = responseDeadline
    timeoutTask?.cancel()
    timeoutTask = Task { @MainActor in
      let remaining = deadline?.timeIntervalSinceNow ?? timeoutSeconds
      if remaining > 0 {
        try? await Task.sleep(for: .seconds(remaining))
      }
      guard !Task.isCancelled else { return }
      applyTimeout(tag: tag)
    }
  }

  private func applyTimeout(tag: UInt32) {
    let isBatch = pendingIsBatch
    let ticket = waitingTicket
    guard claimPendingResponse(tag: tag), let ticket else { return }

    if isBatch {
      logger.warning("Batch trace timeout for tag \(tag)")
      let timeoutResult = TraceResult.timeout(attemptedPath: pendingPathHash ?? [], hashSize: hashSize)
      completedResults.append(timeoutResult)
      recordFailedRun(ticket: ticket)
      pendingPathHash = nil
      resumeContinuationOnce()
    } else {
      logger.warning("Trace timeout for tag \(tag)")
      setError(L10n.Contacts.Contacts.Trace.Error.noResponse)
      pendingPathHash = nil
      recordFailedRun(ticket: ticket)
      isRunning = false
      pendingDeviceID = nil
    }
  }

  /// First owner of the tag wins. The response path and the deadline path both call this before writing.
  private func claimPendingResponse(tag: UInt32) -> Bool {
    guard pendingTag == tag, let waitingTicket, waitingTicket.isCurrent(in: executionEpoch) else { return false }
    pendingTag = nil
    self.waitingTicket = nil
    responseDeadline = nil
    timeoutTask?.cancel()
    timeoutTask = nil
    return true
  }

  private func handleTraceStreamEnded(finished: AdvertisementService) {
    guard pendingTag != nil, let waitingTicket, waitingTicket.isCurrent(in: executionEpoch) else { return }
    guard let next = advertisementService, next !== finished else { return }
    startListening()
  }

  private func resumeContinuationOnce() {
    guard let continuation = traceContinuation else { return }
    traceContinuation = nil
    continuation.resume()
  }

  /// Record a failed run for saved paths
  private func recordFailedRun(ticket: Epoch.Ticket) {
    guard ticket.isCurrent(in: executionEpoch) else { return }
    guard let savedPath = activeSavedPath else { return }

    let failedRun = TracePathRunDTO(
      id: UUID(),
      date: Date(),
      success: false,
      roundTripMs: 0,
      hopsSNR: []
    )
    appendRunIfCurrent(ticket: ticket, pathID: savedPath.id, run: failedRun)
  }

  private func appendRunIfCurrent(ticket: Epoch.Ticket, pathID: UUID, run: TracePathRunDTO) {
    guard ticket.isCurrent(in: executionEpoch), dataStore != nil else { return }
    Task { @MainActor [weak self] in
      guard let self, ticket.isCurrent(in: self.executionEpoch), let dataStore = self.dataStore else { return }
      #if DEBUG
        if let beforeTraceRunAppendForTesting {
          await beforeTraceRunAppendForTesting()
        }
      #endif
      guard ticket.isCurrent(in: self.executionEpoch) else { return }
      do {
        try await dataStore.appendTracePathRun(pathID: pathID, run: run)
        guard ticket.isCurrent(in: self.executionEpoch) else { return }
        if let updated = try await dataStore.fetchSavedTracePath(id: pathID) {
          self.activeSavedPath = updated
        }
      } catch {
        logger.error("Failed to record run: \(error.localizedDescription)")
      }
    }
  }

  /// Cancel any running batch trace
  func cancelBatchTrace() {
    cancelExecution()
  }

  /// Stops local wait and batch scheduling without dropping path or results.
  func cancelExecution() {
    executionEpoch.bump()
    batchCancelled = true
    pendingTag = nil
    waitingTicket = nil
    responseDeadline = nil
    pendingDeviceID = nil
    pendingPathHash = nil
    timeoutTask?.cancel()
    timeoutTask = nil
    resumeContinuationOnce()
    executionTask?.cancel()
    executionTask = nil
    isRunning = false
    currentTraceIndex = 0
  }

  /// Handle trace response from event stream
  func handleTraceResponse(_ traceInfo: TraceInfo, radioID: UUID?) {
    guard traceInfo.tag == pendingTag else {
      logger.debug("Ignoring trace response with non-matching tag \(traceInfo.tag)")
      return
    }

    // Validate device ID if both are available; skip if either is nil
    if let pending = pendingDeviceID, let received = radioID, pending != received {
      logger.warning("Ignoring trace response from different device")
      return
    }

    let ticket = waitingTicket
    guard claimPendingResponse(tag: traceInfo.tag), let ticket else { return }

    // Calculate duration
    let durationMs = if let startTime = traceStartTime {
      Int(Date().timeIntervalSince(startTime) * 1000)
    } else {
      0
    }

    // Build hops from response using receiver attribution model:
    // Each node's SNR shows what it measured when receiving.
    // This answers "how well did this node receive the signal?"
    var hops: [TraceHop] = []
    let deviceName = connectedDevice?.nodeName ?? L10n.Contacts.Contacts.Results.Hop.myDevice
    let path = traceInfo.path

    let deviceLocation = bestAvailableLocation
    let deviceLat = deviceLocation?.coordinate.latitude
    let deviceLon = deviceLocation?.coordinate.longitude

    // Start node has no SNR (it transmitted first, didn't receive anything)
    hops.append(TraceHop(
      hashBytes: nil,
      resolvedName: deviceName,
      snr: 0,
      isStartNode: true,
      isEndNode: false,
      latitude: deviceLat,
      longitude: deviceLon
    ))

    // Intermediate hops - each shows SNR it measured when receiving
    for node in path where node.hashBytes != nil {
      let resolvedName: String?
      var latitude: Double?
      var longitude: Double?

      if let bytes = node.hashBytes {
        let matchingHop = outboundPath.first(where: { $0.hashBytes == bytes })
        let match = matchingHop.flatMap { resolveNode(for: $0) } ?? resolveNode(for: bytes)

        if let match {
          resolvedName = match.resolvableName
          if match.hasLocation {
            latitude = match.latitude
            longitude = match.longitude
          }
        } else {
          resolvedName = matchingHop?.resolvedName
        }
      } else {
        resolvedName = nil
      }

      hops.append(TraceHop(
        hashBytes: node.hashBytes,
        resolvedName: resolvedName,
        snr: node.snr,
        isStartNode: false,
        isEndNode: false,
        latitude: latitude,
        longitude: longitude
      ))
    }

    // End node shows SNR it measured when receiving
    let endSnr = path.last?.snr ?? 0
    hops.append(TraceHop(
      hashBytes: nil,
      resolvedName: deviceName,
      snr: endSnr,
      isStartNode: false,
      isEndNode: true,
      latitude: deviceLat,
      longitude: deviceLon
    ))

    result = TraceResult(
      hops: hops,
      durationMs: durationMs,
      success: true,
      errorMessage: nil,
      tracedPathBytes: pendingPathHash ?? [],
      hashSize: hashSize
    )

    // In batch mode, store result and resume continuation
    if batchEnabled, let result {
      completedResults.append(result)
    } else {
      resultID = UUID()
      resultDismissedWhileVisible = false
      presentedSuccessID = nil
      isRunning = false
    }

    resumeContinuationOnce()

    pendingPathHash = nil
    pendingTag = nil
    pendingDeviceID = nil
    traceStartTime = nil

    // Auto-append run if this is a saved path
    if let savedPath = activeSavedPath, dataStore != nil {
      let hopsSNR = hops
        .filter { !$0.isStartNode && !$0.isEndNode }
        .map(\.snr)
      let runDTO = TracePathRunDTO(
        id: UUID(),
        date: Date(),
        success: true,
        roundTripMs: durationMs,
        hopsSNR: hopsSNR
      )

      appendRunIfCurrent(ticket: ticket, pathID: savedPath.id, run: runDTO)
    }

    logger.info("Trace completed: \(hops.count) hops, \(durationMs)ms")
  }

  // MARK: - Testing Support

  #if DEBUG
    var sendTraceForTesting: (@MainActor (UInt32, UInt8, Data) async throws -> MessageSentInfo)?
    var throwBeforeTagForTesting: (@MainActor () async throws -> Void)?
    var responseTimeoutSecondsForTesting: Double?
    var unackedTimeoutSecondsForTesting: Double?
    var beforeTraceRunAppendForTesting: (@MainActor () async -> Void)?
    var pendingTagForTesting: UInt32? {
      pendingTag
    }

    var hasActiveTimeoutTaskForTesting: Bool {
      guard let timeoutTask else { return false }
      return !timeoutTask.isCancelled
    }

    /// Test helper to set pending tag without running a full trace
    func setPendingTagForTesting(_ tag: UInt32) {
      pendingTag = tag
      waitingTicket = executionEpoch.ticket()
      pendingIsBatch = batchEnabled
    }

    /// Test helper to set pending device ID
    func setPendingDeviceIDForTesting(_ radioID: UUID?) {
      pendingDeviceID = radioID
    }

    /// Test helper to set pending path hash
    func setPendingPathHashForTesting(_ pathHash: [UInt8]?) {
      pendingPathHash = pathHash
    }

    /// Test helper to set contacts for hash resolution
    func setContactsForTesting(_ contacts: [ContactDTO]) {
      allContacts = contacts
      availableRepeaters = contacts.filter { $0.type == .repeater }
      availableRooms = contacts.filter { $0.type == .room }
    }
  #endif
}

// MARK: - HopPickerSource

extension TracePathViewModel: HopPickerSource {
  var currentHopCount: Int {
    outboundPath.count
  }

  /// Trace paths are uncapped; `nil` tells the shared picker not to gate adds
  /// (the `HopPickerSource` default then makes `isPathFull` always `false`).
  var hopLimit: Int? {
    nil
  }

  func appendHop(_ node: some RepeaterResolvable) {
    addNode(node)
    recordRecent(node.publicKey)
  }

  func addCodes(_ input: String) -> CodeInputResult {
    addRepeatersFromCodes(input)
  }

  func classifyCodes(_ input: String) -> [HopCodeClassification] {
    let existing = Set(outboundPath.map(\.hashBytes))
    return HopCodeParser.classify(
      input: input,
      hashSize: hashSize,
      existingHashes: existing,
      remainingCapacity: nil
    ) { hash in
      guard let match = resolveNode(for: hash) else { return nil }
      return (match.publicKey, match.resolvableName)
    }
  }

  /// Trace hop widths the firmware accepts, in bytes (power-of-2 encoding).
  private static let validTraceHashSizes = [1, 2, 4]

  /// When a pasted bulk entry's codes all share one valid trace width that the
  /// radio can honor and differs from the active width, switch to it so the
  /// codes parse instead of failing as invalid. Skips mixed-width, malformed,
  /// or already-matching input so it never wipes a result redundantly.
  func adoptHashSize(forPastedCodes input: String) {
    guard connectedDevice?.supportsTraceHashSizeOverride == true,
          let mode = Self.inferredTraceHashMode(from: input),
          mode != effectiveTraceMode else { return }
    setTraceHashMode(mode)
  }

  /// The single trace hash mode (0/1/2 for 1/2/4 bytes) implied by a
  /// comma-separated bulk paste, or `nil` when the codes are empty, non-hex,
  /// odd-length, mixed-width, or not a valid power-of-2 trace width.
  static func inferredTraceHashMode(from input: String) -> UInt8? {
    let tokens = input
      .split(separator: ",")
      .map { $0.trimmingCharacters(in: .whitespaces) }
      .filter { !$0.isEmpty }
    guard !tokens.isEmpty else { return nil }

    var width: Int?
    for token in tokens {
      guard token.count.isMultiple(of: 2), token.allSatisfy(\.isHexDigit) else { return nil }
      let bytes = token.count / 2
      if let width, width != bytes { return nil }
      width = bytes
    }
    guard let width, validTraceHashSizes.contains(width) else { return nil }
    return UInt8(width.trailingZeroBitCount)
  }

  func recordRecent(_ publicKey: Data) {
    guard let radioID = currentRadioID else { return }
    recentPublicKeys = recents.record(publicKey, into: recentPublicKeys, for: radioID)
  }
}
