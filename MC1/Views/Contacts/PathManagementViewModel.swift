import SwiftUI
import MC1Services
import os.log

private let logger = Logger(subsystem: "com.mc1", category: "PathManagement")

/// Represents a single hop in the routing path with stable identity for SwiftUI
struct PathHop: Identifiable, Equatable {
    let id = UUID()
    var hashBytes: Data           // Public key prefix bytes (1–3 bytes depending on hash mode)
    var publicKey: Data?          // Full 32-byte key when known (for unambiguous matching)
    var resolvedName: String?     // Contact name if resolved, nil if unknown

    var hashHex: String {
        hashBytes.hexString()
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
    case success(hopCount: Int, fromCache: Bool = false)
    case noPathFound
    case failed(String)

    var description: String {
        switch self {
        case .success(let hopCount, let fromCache):
            let pathType: String
            if hopCount == 0 {
                pathType = L10n.Contacts.Contacts.PathDiscovery.direct
            } else if hopCount == 1 {
                pathType = L10n.Contacts.Contacts.PathDiscovery.Hops.singular
            } else {
                pathType = L10n.Contacts.Contacts.PathDiscovery.Hops.plural(hopCount)
            }
            let source = fromCache ? L10n.Contacts.Contacts.PathDiscovery.cachedSuffix : ""
            return "\(pathType)\(source)"
        case .noPathFound:
            return L10n.Contacts.Contacts.PathDiscovery.noResponse
        case .failed(let message):
            return L10n.Contacts.Contacts.PathDiscovery.failed(message)
        }
    }
}

@MainActor @Observable
final class PathManagementViewModel {
    private enum DiscoveryTimeout {
        static let minimumSeconds = 5.0
        static let maximumSeconds = 60.0
        static let defaultSeconds = 30.0
        static let multiplier = 1.2
    }

    // MARK: - State

    var isDiscovering = false
    var isSettingPath = false
    var discoveryResult: PathDiscoveryResult?
    var showDiscoveryResult = false
    var errorMessage: String?
    var showError = false

    // Path editing state
    var showingPathEditor = false
    var editablePath: [PathHop] = []  // Current path being edited (stable identifiers)
    var availableRepeaters: [ContactDTO] = []  // Known repeaters to add
    var availableRooms: [ContactDTO] = []  // Known rooms (may act as repeaters)
    var allContacts: [ContactDTO] = []  // All contacts for name resolution
    var discoveredNodes: [DiscoveredNodeDTO] = []

    /// Combined repeaters and rooms for resolution
    var availableNodes: [ContactDTO] {
        availableRepeaters + availableRooms
    }

    /// Discovered repeaters available to add
    var discoveredRepeaters: [DiscoveredNodeDTO] {
        discoveredNodes.filter { $0.nodeType == .repeater }
    }

    /// Repeaters available to add (allows duplicates for paths like A → B → A)
    var filteredAvailableRepeaters: [ContactDTO] {
        availableRepeaters
    }

    // Discovery cancellation
    private var discoveryTask: Task<Void, Never>?

    // Discovery countdown state
    var discoverySecondsRemaining: Int?
    private var countdownTask: Task<Void, Never>?
    private var discoveryStartTime: Date?
    private var discoveryTimeoutSeconds: Double?

    // MARK: - Dependencies

    private var appState: AppState?

    /// Current hash size from device configuration (1, 2, or 3 bytes per hop)
    var hashSize: Int {
        appState?.connectedDevice?.hashSize ?? 1
    }

    // MARK: - Callbacks

    /// Called when path discovery completes and contact should be refreshed
    var onContactNeedsRefresh: (() -> Void)?

    // MARK: - Configuration

    func configure(appState: AppState, onContactNeedsRefresh: @escaping () -> Void) {
        self.appState = appState
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
        return nil  // Ambiguous (multiple matches) or unknown
    }

    /// Create a PathHop from hash bytes, resolving the name if possible
    func createPathHop(from hashBytes: Data) -> PathHop {
        PathHop(hashBytes: hashBytes, resolvedName: resolveHashToName(hashBytes))
    }

    /// Load all contacts for name resolution and filter repeaters for adding
    /// Skips fetch if contacts are already loaded for the same device
    func loadContacts(radioID: UUID, forceReload: Bool = false) async {
        guard let appState,
              let dataStore = appState.services?.dataStore else { return }

        // Skip if already loaded
        if !forceReload && !allContacts.isEmpty {
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

    /// Initialize editable path from contact's current path with name resolution
    func initializeEditablePath(from contact: ContactDTO) {
        let byteLength = contact.pathByteLength
        let hashSize = contact.pathHashSize
        let pathData = contact.outPath.prefix(byteLength)
        editablePath = stride(from: 0, to: pathData.count, by: hashSize).map { start in
            let end = min(start + hashSize, pathData.count)
            let bytes = Data(pathData[start..<end])
            return createPathHop(from: bytes)
        }
    }

    /// Add a node to the path using its public key prefix
    func addNode(_ node: some RepeaterResolvable) {
        let hashBytes = Data(node.publicKey.prefix(hashSize))
        let hop = PathHop(hashBytes: hashBytes, publicKey: node.publicKey, resolvedName: node.resolvableName)
        editablePath.append(hop)
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

    /// Save the edited path to the contact
    func saveEditedPath(for contact: ContactDTO) async {
        guard let appState,
              let contactService = appState.services?.contactService else { return }

        isSettingPath = true
        errorMessage = nil

        do {
            let pathData = Data(editablePath.flatMap { $0.hashBytes })
            let hashSize = editablePath.first?.hashBytes.count ?? 1 // empty path encodes as direct (outPathLength == 0)
            let pathLength = encodePathLen(hashSize: hashSize, hopCount: editablePath.count)
            try await contactService.setPath(
                radioID: contact.radioID,
                publicKey: contact.publicKey,
                path: pathData,
                pathLength: pathLength
            )
            onContactNeedsRefresh?()
        } catch {
            errorMessage = L10n.Contacts.Contacts.PathManagement.Error.saveFailed(error.localizedDescription)
            showError = true
        }

        isSettingPath = false
    }

    // MARK: - Path Operations

    nonisolated static func sanitizedDiscoveryTimeoutSeconds(suggestedTimeoutMs: UInt32) -> Double {
        let candidateSeconds = (Double(suggestedTimeoutMs) / 1000.0) * DiscoveryTimeout.multiplier
        guard candidateSeconds >= DiscoveryTimeout.minimumSeconds,
              candidateSeconds <= DiscoveryTimeout.maximumSeconds else {
            return DiscoveryTimeout.defaultSeconds
        }
        return candidateSeconds
    }

    /// Initiate path discovery for a contact (with cancel support)
    /// Uses two-tier approach:
    /// 1. First perform active discovery to get fresh path (requires remote response)
    /// 2. If timeout, fall back to cached advertisement path
    func discoverPath(for contact: ContactDTO) async {
        guard let appState,
              let contactService = appState.services?.contactService else { return }

        // Cancel any existing discovery
        discoveryTask?.cancel()

        isDiscovering = true
        discoveryResult = nil
        errorMessage = nil

        // Tier 1: Perform active path discovery (requires remote node response)
        discoveryTask = Task { @MainActor in
            do {
                let sentResponse = try await contactService.sendPathDiscovery(
                    radioID: contact.radioID,
                    publicKey: contact.publicKey
                )

                let candidateSeconds = (Double(sentResponse.suggestedTimeoutMs) / 1000.0) * DiscoveryTimeout.multiplier
                let timeoutSeconds = Self.sanitizedDiscoveryTimeoutSeconds(suggestedTimeoutMs: sentResponse.suggestedTimeoutMs)
                if timeoutSeconds == DiscoveryTimeout.defaultSeconds && candidateSeconds != timeoutSeconds {
                    logger.warning(
                        "Path discovery timeout fallback applied: raw=\(sentResponse.suggestedTimeoutMs)ms, candidate=\(candidateSeconds)s, fallback=\(timeoutSeconds)s"
                    )
                } else {
                    logger.info("Path discovery timeout: \(timeoutSeconds)s (firmware suggested: \(sentResponse.suggestedTimeoutMs)ms)")
                }

                // Start countdown timer
                self.discoveryTimeoutSeconds = timeoutSeconds
                self.discoveryStartTime = Date.now
                self.discoverySecondsRemaining = Int(timeoutSeconds)
                self.startCountdownTask()

                // Wait for push notification with firmware-suggested timeout
                // The AdvertisementService handler will call handleDiscoveryResponse()
                // which cancels this task early if a response arrives
                try await Task.sleep(for: .seconds(timeoutSeconds))

                if !Task.isCancelled {
                    // Timeout - remote node did not respond
                    // Tier 2: Fall back to cached advertisement path
                    await fallbackToCachedPath(for: contact)
                }
            } catch is CancellationError {
                // User cancelled or response received - no feedback needed
            } catch {
                discoveryResult = .failed(error.localizedDescription)
                showDiscoveryResult = true
            }

            isDiscovering = false
            cleanupCountdownState()
        }
    }

    /// Handle timeout when active discovery doesn't receive a response
    private func fallbackToCachedPath(for contact: ContactDTO) async {
        // Active discovery timed out - remote node did not respond
        discoveryResult = .noPathFound
        showDiscoveryResult = true
    }

    /// Start the countdown task that updates remaining seconds every 5 seconds
    private func startCountdownTask() {
        countdownTask = Task {
            while !Task.isCancelled, let timeout = discoveryTimeoutSeconds, let startTime = discoveryStartTime {
                do {
                    try await Task.sleep(for: .seconds(5))
                } catch {
                    break
                }

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

    /// Called when a path discovery response is received via push notification
    func handleDiscoveryResponse(hopCount: Int) {
        discoveryTask?.cancel()
        isDiscovering = false
        cleanupCountdownState()

        // hopCount == 0 means direct path (zero hops via repeaters)
        // hopCount > 0 means routed path through repeaters
        // Both are successful discoveries
        discoveryResult = .success(hopCount: hopCount, fromCache: false)
        showDiscoveryResult = true

        // Signal that contact data should be refreshed to show new path
        onContactNeedsRefresh?()
    }

    /// Reset the path for a contact (force flood routing)
    func resetPath(for contact: ContactDTO) async {
        guard let appState,
              let contactService = appState.services?.contactService else { return }

        isSettingPath = true
        errorMessage = nil

        do {
            try await contactService.resetPath(
                radioID: contact.radioID,
                publicKey: contact.publicKey
            )
            onContactNeedsRefresh?()
        } catch {
            errorMessage = L10n.Contacts.Contacts.PathManagement.Error.resetFailed(error.localizedDescription)
            showError = true
        }

        isSettingPath = false
    }

    /// Set a specific path for a contact
    func setPath(for contact: ContactDTO, path: Data, pathLength: UInt8) async {
        guard let appState,
              let contactService = appState.services?.contactService else { return }

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
            errorMessage = L10n.Contacts.Contacts.PathManagement.Error.setFailed(error.localizedDescription)
            showError = true
        }

        isSettingPath = false
    }
}
