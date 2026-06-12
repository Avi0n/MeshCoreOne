import MeshCore
import OSLog
import MC1Services
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

struct NodeDiscoveryResult: Identifiable, Sendable {
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

    private var session: MeshCoreSession?
    private var dataStore: PersistenceStore?
    private var radioID: UUID?
    private var contactService: ContactService?
    private var maxContacts: UInt16?

    // MARK: - Tasks

    private var scanTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?

    // MARK: - Name resolution cache

    private var namesByKey: [Data: String] = [:]

    // MARK: - Configuration

    /// Configure with the session, store, and services this view model uses; nil mirrors a disconnected state.
    func configure(
        session: MeshCoreSession?,
        dataStore: PersistenceStore?,
        radioID: UUID?,
        contactService: ContactService?,
        maxContacts: UInt16?
    ) {
        self.session = session
        self.dataStore = dataStore
        self.radioID = radioID
        self.contactService = contactService
        self.maxContacts = maxContacts
    }

    // MARK: - Scan

    func scan() {
        guard let session else {
            errorMessage = L10n.Tools.Tools.NodeDiscovery.notConnectedDescription(filter.localizedTitle)
            return
        }

        guard let radioID else { return }

        stopScan()
        results.removeAll { $0.scanFilter == filter }
        errorMessage = nil
        isScanning = true
        scanStartHapticTrigger += 1

        scanTask = Task { [weak self] in
            guard let self else { return }

            do {
                // Pre-load name resolution data and existing contact keys
                await self.loadNameResolutionData(radioID: radioID)

                // Send discovery request
                let tag = try await session.sendNodeDiscoverRequest(
                    filter: self.filter.filterValue,
                    prefixOnly: false
                )
                let tagData = withUnsafeBytes(of: tag.littleEndian) { Data($0) }

                // Start timeout that cancels the scan task
                self.timeoutTask = Task { [weak self] in
                    try? await Task.sleep(for: Self.scanDuration)
                    self?.scanTask?.cancel()
                }

                // Listen for responses
                let events = await session.events()
                for await event in events {
                    guard !Task.isCancelled else { break }

                    if case .discoverResponse(let response) = event,
                       response.tag == tagData {
                        self.appendOrUpdateResult(from: response)
                    }
                }
            } catch is CancellationError {
                // Normal timeout cancellation — not an error
            } catch {
                Self.logger.error("Node discovery failed: \(error.localizedDescription)")
                self.errorMessage = error.userFacingMessage
            }

            self.finishScan()
        }
    }

    func stopScan() {
        timeoutTask?.cancel()
        timeoutTask = nil
        scanTask?.cancel()
        scanTask = nil
        if isScanning {
            finishScan()
        }
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

    private func loadNameResolutionData(radioID: UUID) async {
        guard let dataStore else { return }
        do {
            // Load discovered nodes first, then contacts — contacts take priority
            let nodes = try await dataStore.fetchDiscoveredNodes(radioID: radioID)
            namesByKey = Dictionary(
                nodes.map { ($0.publicKey, $0.name) },
                uniquingKeysWith: { first, _ in first }
            )
            let contacts = try await dataStore.fetchContacts(radioID: radioID)
            for contact in contacts {
                namesByKey[contact.publicKey] = contact.name
            }
            addedPublicKeys = Set(contacts.map(\.publicKey))
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
        guard let contactService, let radioID else { return }

        addingPublicKey = result.publicKey
        Task { [weak self] in
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
                try await contactService.addOrUpdateContact(radioID: radioID, contact: contact)
                self?.addedPublicKeys.insert(result.publicKey)
                self?.addSuccessHapticTrigger += 1
            } catch ContactServiceError.contactTableFull {
                if let maxContacts = self?.maxContacts {
                    self?.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFull(Int(maxContacts))
                } else {
                    self?.errorMessage = L10n.Contacts.Contacts.Add.Error.nodeListFullSimple
                }
                self?.addErrorHapticTrigger += 1
            } catch {
                self?.errorMessage = error.userFacingMessage
                self?.addErrorHapticTrigger += 1
            }
            self?.addingPublicKey = nil
        }
    }
}
