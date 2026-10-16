import MC1Services
import MeshCore
import OSLog
import SwiftUI

// MARK: - Filter & Sort

enum NodeDiscoveryFilter: String, CaseIterable {
  case repeaters
  case sensors

  var filterValue: UInt8 {
    switch self {
    case .repeaters: 0x04
    case .sensors: 0x10
    }
  }

  var localizedTitle: String {
    switch self {
    case .repeaters: L10n.Tools.Tools.NodeDiscovery.repeaters
    case .sensors: L10n.Tools.Tools.NodeDiscovery.sensors
    }
  }
}

enum NodeDiscoverySortOrder: String, CaseIterable {
  case snr
  case name

  var localizedTitle: String {
    switch self {
    case .snr: L10n.Tools.Tools.NodeDiscovery.sortSignal
    case .name: L10n.Tools.Tools.NodeDiscovery.sortName
    }
  }
}

// MARK: - Result

struct NodeDiscoveryResult: Identifiable {
  let id = UUID()
  let name: String
  let publicKey: Data
  let nodeType: UInt8
  let snr: Double
  let snrIn: Double
  let rssi: Int
  let scanFilter: NodeDiscoveryFilter
  let receivedAt: Date
}

// MARK: - View Model

@Observable
@MainActor
final class NodeDiscoveryViewModel {
  private static let logger = Logger(subsystem: "com.mc1", category: "NodeDiscoveryViewModel")
  private static let scanDuration: Duration = .seconds(15)

  // MARK: - Published state

  var results: [NodeDiscoveryResult] = []
  var isScanning = false
  var errorMessage: String?
  var filter: NodeDiscoveryFilter = .repeaters
  var sortOrder: NodeDiscoverySortOrder = .snr

  var scanStartHapticTrigger = 0
  var scanSuccessHapticTrigger = 0
  var scanEmptyHapticTrigger = 0

  var addedPublicKeys: Set<Data> = []
  var addingPublicKey: Data?
  var addSuccessHapticTrigger = 0
  var addErrorHapticTrigger = 0

  // MARK: - Dependencies

  struct Dependencies {
    var session: @MainActor () -> MeshCoreSession?
    var dataStore: @MainActor () -> PersistenceStore?
    var radioID: @MainActor () -> UUID?
    var contactService: @MainActor () -> ContactService?
    var maxContacts: @MainActor () -> UInt16?
  }

  private var deps = Dependencies(
    session: { nil },
    dataStore: { nil },
    radioID: { nil },
    contactService: { nil },
    maxContacts: { nil }
  )

  private var session: MeshCoreSession? {
    deps.session()
  }

  private var dataStore: PersistenceStore? {
    deps.dataStore()
  }

  private var radioID: UUID? {
    deps.radioID()
  }

  private var contactService: ContactService? {
    deps.contactService()
  }

  private var maxContacts: UInt16? {
    deps.maxContacts()
  }

  // MARK: - Tasks

  private var scanTask: Task<Void, Never>?
  private var timeoutTask: Task<Void, Never>?
  private var scanEpoch = Epoch()
  private var scanTicket: Epoch.Ticket?
  private var scanDeadline: Date?
  private var acceptResponses = false
  private var listenGeneration: UInt = 0
  private var listenWake: CheckedContinuation<Void, Never>?
  private var addEpochs: [Data: Epoch] = [:]
  private var addTickets: [Data: Epoch.Ticket] = [:]

  #if DEBUG
    var loadNamesForTesting: (@MainActor () async -> Void)?
    var sendDiscoverForTesting: (@MainActor (UInt8) async throws -> UInt32)?
    var addContactForTesting: (@MainActor (ContactFrame) async throws -> Void)?
    var scanDurationForTesting: TimeInterval?
    var listenLoopIterationsForTesting = 0
    var isListeningForTesting = false
    var isWaitingForSessionForTesting = false

    func setScanDeadlineForTesting(_ date: Date) {
      scanDeadline = date
    }
  #endif

  // MARK: - Name resolution cache

  private var namesByKey: [Data: String] = [:]

  // MARK: - Configuration

  /// Configure with the session, store, and services this view model uses; a provider returning nil mirrors a disconnected state.
  func configure(dependencies: Dependencies) {
    deps = dependencies
  }

  // MARK: - Scan

  func scan() {
    guard canStartScan else {
      errorMessage = L10n.Tools.Tools.NodeDiscovery.notConnectedDescription(filter.localizedTitle)
      return
    }
    guard radioID != nil || hasScanTestHook else { return }

    stopScan()
    let ticket = scanEpoch.ticket()
    scanTicket = ticket
    let activeFilter = filter
    results.removeAll { $0.scanFilter == activeFilter }
    errorMessage = nil
    isScanning = true
    acceptResponses = true
    scanStartHapticTrigger += 1

    scanTask = Task { [weak self] in
      guard let self else { return }
      do {
        if let radioID = self.radioID {
          await self.loadNameResolutionData(radioID: radioID, ticket: ticket)
        }
        guard ticket.isCurrent(in: self.scanEpoch), !Task.isCancelled else { return }

        let tag = try await self.sendDiscover(filter: activeFilter.filterValue)
        guard ticket.isCurrent(in: self.scanEpoch), !Task.isCancelled else { return }
        let tagData = withUnsafeBytes(of: tag.littleEndian) { Data($0) }
        self.armScanDeadline(ticket: ticket)
        await self.listen(ticket: ticket, tag: tagData)
      } catch is CancellationError {
        return
      } catch {
        guard ticket.isCurrent(in: self.scanEpoch) else { return }
        Self.logger.error("Node discovery failed: \(error.localizedDescription)")
        self.errorMessage = error.userFacingMessage
        self.finishScan()
      }
    }
  }

  /// Hiding the tool does not stop a scan.
  func setWorkspaceVisible(_ visible: Bool) {
    _ = visible
  }

  /// Same-device ready. Wakes a scan that is waiting for a session.
  func reattachIfWaiting() {
    guard isScanning, acceptResponses else { return }
    wakeListener()
  }

  /// Foreground return. A deadline that passed while suspended finishes the scan.
  func expireScanIfDeadlinePassed() {
    guard isScanning, let deadline = scanDeadline, Date() >= deadline else { return }
    guard let scanTicket, scanTicket.isCurrent(in: scanEpoch) else { return }
    finishScan()
  }

  func stopScan() {
    scanEpoch.bump()
    listenGeneration &+= 1
    scanTicket = nil
    scanDeadline = nil
    acceptResponses = false
    wakeListener()
    timeoutTask?.cancel()
    timeoutTask = nil
    scanTask?.cancel()
    scanTask = nil
    if isScanning {
      finishScan()
    }
  }

  /// Drops scan results. Does not publish a timeout into `errorMessage`.
  func reset() {
    for key in Array(addEpochs.keys) {
      addEpochs[key]?.bump()
    }
    stopScan()
    results.removeAll()
    addedPublicKeys.removeAll()
    addingPublicKey = nil
    errorMessage = nil
  }

  // MARK: - Sorted results

  var sortedResults: [NodeDiscoveryResult] {
    let filtered = results.filter { $0.scanFilter == filter }
    return switch sortOrder {
    case .snr:
      filtered.sorted { $0.snr > $1.snr }
    case .name:
      filtered.sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }
  }

  // MARK: - Private

  private var canStartScan: Bool {
    session != nil || hasScanTestHook
  }

  private var hasScanTestHook: Bool {
    #if DEBUG
      sendDiscoverForTesting != nil
    #else
      false
    #endif
  }

  private var scanSeconds: TimeInterval {
    #if DEBUG
      if let scanDurationForTesting {
        return scanDurationForTesting
      }
    #endif
    return 15
  }

  private func sendDiscover(filter: UInt8) async throws -> UInt32 {
    #if DEBUG
      if let sendDiscoverForTesting {
        return try await sendDiscoverForTesting(filter)
      }
    #endif
    guard let session else { throw CancellationError() }
    return try await session.sendNodeDiscoverRequest(filter: filter, prefixOnly: false)
  }

  private func armScanDeadline(ticket: Epoch.Ticket) {
    let seconds = scanSeconds
    scanDeadline = Date().addingTimeInterval(seconds)
    timeoutTask?.cancel()
    timeoutTask = Task { [weak self] in
      if seconds > 0 {
        try? await Task.sleep(for: .seconds(seconds))
      }
      guard let self, ticket.isCurrent(in: self.scanEpoch) else { return }
      self.finishScan()
    }
  }

  private func listen(ticket: Epoch.Ticket, tag: Data) async {
    let discoverFilter = EventFilter.eventType { event in
      if case .discoverResponse = event { return true }
      return false
    }
    while ticket.isCurrent(in: scanEpoch), acceptResponses, !Task.isCancelled {
      guard let session else {
        await waitForSession()
        continue
      }
      let generation = listenGeneration
      let subscribed = session
      #if DEBUG
        isListeningForTesting = true
      #endif
      let events = await session.events(filter: discoverFilter)
      for await event in events {
        #if DEBUG
          listenLoopIterationsForTesting += 1
        #endif
        guard generation == listenGeneration, ticket.isCurrent(in: scanEpoch), acceptResponses else { break }
        if case let .discoverResponse(response) = event, response.tag == tag {
          appendOrUpdateResult(from: response)
        }
      }
      #if DEBUG
        if generation == listenGeneration {
          isListeningForTesting = false
        }
      #endif
      guard ticket.isCurrent(in: scanEpoch), acceptResponses else { return }
      let next = self.session
      if next == nil || next === subscribed {
        await waitForSession()
      }
    }
  }

  private func waitForSession() async {
    #if DEBUG
      isWaitingForSessionForTesting = true
    #endif
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      listenWake = continuation
    }
    #if DEBUG
      isWaitingForSessionForTesting = false
    #endif
  }

  private func wakeListener() {
    listenWake?.resume()
    listenWake = nil
  }

  private func loadNameResolutionData(radioID: UUID, ticket: Epoch.Ticket) async {
    #if DEBUG
      if let loadNamesForTesting {
        await loadNamesForTesting()
      }
    #endif
    guard ticket.isCurrent(in: scanEpoch) else { return }
    guard let dataStore else { return }
    do {
      let nodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
      guard ticket.isCurrent(in: scanEpoch) else { return }
      namesByKey = Dictionary(
        nodes.map { ($0.publicKey, $0.name) },
        uniquingKeysWith: { first, _ in first }
      )
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
      guard ticket.isCurrent(in: scanEpoch) else { return }
      for contact in contacts {
        namesByKey[contact.publicKey] = contact.name
      }
      let storeKeys = Set(contacts.map(\.publicKey))
      let inFlight = Set(addTickets.compactMap { key, addTicket -> Data? in
        guard let epoch = addEpochs[key], addTicket.isCurrent(in: epoch) else { return nil }
        return key
      })
      addedPublicKeys = storeKeys.union(inFlight)
    } catch {
      Self.logger.error("Failed to load name resolution data: \(error.localizedDescription)")
    }
  }

  private func resolveName(for publicKey: Data) -> String {
    if let name = namesByKey[publicKey] {
      return name
    }
    let hexPrefix = publicKey.prefix(4).map { String(format: "%02X", $0) }.joined()
    return "\(L10n.Tools.Tools.NodeDiscovery.unknownNode) (\(hexPrefix))"
  }

  private func appendOrUpdateResult(from response: DiscoverResponse) {
    let result = NodeDiscoveryResult(
      name: resolveName(for: response.publicKey),
      publicKey: response.publicKey,
      nodeType: response.nodeType,
      snr: response.snr,
      snrIn: response.snrIn,
      rssi: response.rssi,
      scanFilter: filter,
      receivedAt: Date()
    )
    if let existingIndex = results.firstIndex(where: { $0.publicKey == response.publicKey && $0.scanFilter == filter }) {
      results[existingIndex] = result
    } else {
      results.append(result)
    }
  }

  private func finishScan() {
    isScanning = false
    if results.contains(where: { $0.scanFilter == filter }) {
      scanSuccessHapticTrigger += 1
    } else {
      scanEmptyHapticTrigger += 1
    }
  }

  // MARK: - Add Node

  func isAdded(publicKey: Data) -> Bool {
    addedPublicKeys.contains(publicKey)
  }

  func addNode(_ result: NodeDiscoveryResult) {
    guard (contactService != nil && radioID != nil) || hasAddTestHook else { return }

    var epoch = addEpochs[result.publicKey] ?? Epoch()
    epoch.bump()
    addEpochs[result.publicKey] = epoch
    let ticket = epoch.ticket()
    addTickets[result.publicKey] = ticket
    let capturedRadioID = radioID

    addingPublicKey = result.publicKey
    Task { [weak self] in
      guard let self else { return }
      do {
        let contact = ContactFrame(
          publicKey: result.publicKey,
          type: ContactType(rawValue: result.nodeType) ?? .repeater,
          flags: 0,
          outPathLength: PacketBuilder.floodPathSentinel,
          outPath: Data(),
          name: result.name,
          lastAdvertTimestamp: 0,
          latitude: 0,
          longitude: 0,
          lastModified: 0
        )
        try await self.performAdd(contact: contact, capturedRadioID: capturedRadioID)
        guard self.addTicketIsCurrent(result.publicKey) else { return }
        self.addedPublicKeys.insert(result.publicKey)
        self.addSuccessHapticTrigger += 1
      } catch ContactServiceError.contactTableFull {
        guard self.addTicketIsCurrent(result.publicKey) else { return }
        if let maxContacts = self.maxContacts {
          self.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts))
        } else {
          self.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFullSimple
        }
        self.addErrorHapticTrigger += 1
      } catch {
        guard self.addTicketIsCurrent(result.publicKey) else { return }
        self.errorMessage = error.userFacingMessage
        self.addErrorHapticTrigger += 1
      }
      guard self.addTicketIsCurrent(result.publicKey) else { return }
      self.addingPublicKey = nil
    }
  }

  private var hasAddTestHook: Bool {
    #if DEBUG
      addContactForTesting != nil
    #else
      false
    #endif
  }

  private func performAdd(contact: ContactFrame, capturedRadioID: UUID?) async throws {
    #if DEBUG
      if let addContactForTesting {
        try await addContactForTesting(contact)
        return
      }
    #endif
    guard let contactService, let capturedRadioID else { throw CancellationError() }
    try await contactService.addOrUpdateContact(radioID: capturedRadioID, contact: contact) {
      self.radioID
    }
  }

  private func addTicketIsCurrent(_ publicKey: Data) -> Bool {
    guard let ticket = addTickets[publicKey], let epoch = addEpochs[publicKey] else { return false }
    return ticket.isCurrent(in: epoch)
  }
}
