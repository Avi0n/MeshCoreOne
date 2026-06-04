import OSLog
import MC1Services
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "RepeaterStatusVM")

/// ViewModel for repeater status display
@Observable
@MainActor
final class RepeaterStatusViewModel {

    // MARK: - Shared Helper

    var helper = NodeStatusHelper()

    // MARK: - Repeater-Only Properties

    /// Neighbor entries
    var neighbors: [NeighbourInfo] = []

    /// Loading states
    var isLoadingNeighbors = false

    /// Whether neighbors have been loaded at least once (for refresh logic)
    var neighborsLoaded = false

    /// Whether the neighbors disclosure group is expanded
    var neighborsExpanded = false

    /// Error scoped to the neighbors section, kept separate from other sections' errors.
    var neighborsSectionError: String?

    /// Discovery state
    var isDiscovering: Bool { discoverTask != nil }
    var discoverySecondsRemaining = 0
    private var discoverTask: Task<Void, Never>?

    private static let discoveryDuration = 60
    private static let pollIntervalTicks = 5
    private static let discoverCommand = "discover.neighbors"

    /// Owner info text
    var ownerInfo: String?

    /// Owner info loading/state
    var isLoadingOwnerInfo = false
    var ownerInfoLoaded: Bool { ownerInfo != nil }
    var ownerInfoExpanded = false
    var ownerInfoError: String?

    // MARK: - Dependencies

    private var repeaterAdminService: RepeaterAdminService?

    // MARK: - Initialization

    init() {}

    func configure(appState: AppState) {
        self.repeaterAdminService = appState.services?.repeaterAdminService
        helper.configure(
            contactService: appState.services?.contactService,
            nodeSnapshotService: appState.services?.nodeSnapshotService
        )
    }

    func registerHandlers(appState: AppState) async {
        guard let repeaterAdminService = appState.services?.repeaterAdminService else { return }

        // Set only the slots this view model owns. The admin service is shared
        // with the settings/CLI view model, so clearing here would drop its CLI
        // handler and silently break late CLI-response delivery.
        await repeaterAdminService.setStatusHandler { [weak self] status in
            await self?.handleStatusResponse(status)
        }

        await repeaterAdminService.setNeighboursHandler { [weak self] response in
            await self?.handleNeighboursResponse(response)
        }

        await repeaterAdminService.setTelemetryHandler { [weak self] response in
            await self?.helper.handleTelemetryResponse(response)
        }
    }

    /// Clear every handler slot on the shared admin service. Only for true
    /// surface teardown (sheet dismiss); calling it on a segment switch would
    /// wipe the CLI handler the settings view model relies on.
    func cleanup(appState: AppState) async {
        guard let repeaterAdminService = appState.services?.repeaterAdminService else { return }
        await repeaterAdminService.clearHandlers()
    }

    /// Clear only this view model's status/neighbours/telemetry handler slots, leaving the
    /// settings view model's CLI handler intact. For the merged admin surface's status-segment teardown.
    func clearStatusHandlers(appState: AppState) async {
        guard let repeaterAdminService = appState.services?.repeaterAdminService else { return }
        await repeaterAdminService.clearStatusHandlers()
    }

    // MARK: - Status

    func requestStatus(for session: RemoteNodeSessionDTO) async {
        guard let repeaterAdminService else { return }
        if helper.session == nil { helper.session = session }

        await helper.runRetryingSectionRequest(
            operationName: "status",
            setLoading: { self.helper.isLoadingStatus = $0 },
            setError: { self.helper.statusSectionError = $0 },
            operation: { [repeaterAdminService] timeout in
                try await repeaterAdminService.requestStatus(sessionID: session.id, timeout: timeout)
            },
            onSuccess: { await self.handleStatusResponse($0) }
        )
    }

    private func handleStatusResponse(_ response: RemoteNodeStatus) async {
        await helper.handleStatusResponse(
            response,
            rxAirtimeSeconds: response.repeaterRxAirtimeSeconds,
            receiveErrors: response.receiveErrors
        )
    }

    // MARK: - Neighbors

    func requestNeighbors(for session: RemoteNodeSessionDTO) async {
        guard let repeaterAdminService else { return }
        if helper.session == nil { helper.session = session }

        await helper.runRetryingSectionRequest(
            operationName: "neighbors",
            setLoading: { self.isLoadingNeighbors = $0 },
            setError: { self.neighborsSectionError = $0 },
            operation: { [repeaterAdminService] timeout in
                try await repeaterAdminService.requestNeighbors(sessionID: session.id, timeout: timeout)
            },
            onSuccess: { await self.handleNeighboursResponse($0) }
        )
    }

    func handleNeighboursResponse(_ response: NeighboursResponse) async {
        self.neighbors = response.neighbours
        self.isLoadingNeighbors = false
        self.neighborsLoaded = true

        let entries = response.neighbours.map {
            NeighborSnapshotEntry(publicKeyPrefix: $0.publicKeyPrefix, snr: $0.snr, secondsAgo: $0.secondsAgo)
        }
        await helper.enrichNeighbors(entries)
    }

    // MARK: - Discovery

    func startDiscovery(for session: RemoteNodeSessionDTO) {
        guard let repeaterAdminService, !isDiscovering else { return }

        discoverySecondsRemaining = Self.discoveryDuration

        discoverTask = Task {
            do {
                _ = try await repeaterAdminService.sendCommand(
                    sessionID: session.id,
                    command: Self.discoverCommand
                )
            } catch {
                neighborsSectionError = error.localizedDescription
                discoverySecondsRemaining = 0
                discoverTask = nil
                return
            }

            let startTime = Date.now
            var tickCount = 0

            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                guard !Task.isCancelled else { break }

                let elapsed = Int(Date.now.timeIntervalSince(startTime))
                let remaining = max(0, Self.discoveryDuration - elapsed)
                discoverySecondsRemaining = remaining

                tickCount += 1
                if tickCount.isMultiple(of: Self.pollIntervalTicks) {
                    await requestNeighbors(for: session)
                }

                if remaining <= 0 { break }
            }

            discoverySecondsRemaining = 0
            discoverTask = nil
        }
    }

    func stopDiscovery() {
        discoverTask?.cancel()
        discoverTask = nil
        discoverySecondsRemaining = 0
    }

    // MARK: - Telemetry

    func requestTelemetry(for session: RemoteNodeSessionDTO) async {
        guard let repeaterAdminService else { return }
        if helper.session == nil { helper.session = session }

        await helper.runRetryingSectionRequest(
            operationName: "telemetry",
            setLoading: { self.helper.isLoadingTelemetry = $0 },
            setError: { self.helper.telemetrySectionError = $0 },
            operation: { [repeaterAdminService] timeout in
                try await repeaterAdminService.requestTelemetry(sessionID: session.id, timeout: timeout)
            },
            onSuccess: { await self.helper.handleTelemetryResponse($0) }
        )
    }

    // MARK: - Owner Info

    func requestOwnerInfo(for session: RemoteNodeSessionDTO) async {
        guard let repeaterAdminService else { return }
        if helper.session == nil { helper.session = session }

        await helper.runRetryingSectionRequest(
            operationName: "ownerInfo",
            setLoading: { self.isLoadingOwnerInfo = $0 },
            setError: { self.ownerInfoError = $0 },
            operation: { [repeaterAdminService] timeout in
                try await repeaterAdminService.requestOwnerInfo(sessionID: session.id, timeout: timeout)
            },
            onSuccess: { self.ownerInfo = $0.ownerInfo }
        )
    }

    // MARK: - Repeater-Only Display

    var receiveErrorsDisplay: String? {
        guard let count = helper.status?.receiveErrors, count > 0 else { return nil }
        return count.formatted()
    }
}
