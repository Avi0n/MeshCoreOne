import MC1Services
import SwiftUI

/// ViewModel for room server status display
@Observable
@MainActor
final class RoomStatusViewModel {

    // MARK: - Shared Helper

    var helper = NodeStatusViewModel()

    // MARK: - Dependencies

    private var roomAdminService: RoomAdminService?

    // MARK: - Initialization

    init() {}

    /// Nil services mirror a disconnected state; requests then no-op.
    func configure(
        roomAdminService: RoomAdminService?,
        contactService: ContactService?,
        nodeSnapshotService: NodeSnapshotService?
    ) {
        self.roomAdminService = roomAdminService
        helper.configure(
            contactService: contactService,
            nodeSnapshotService: nodeSnapshotService
        )
    }

    /// Takes the service per call so a reconnect-minted instance is read at
    /// call time rather than the configure-time snapshot.
    func registerHandlers(roomAdminService: RoomAdminService?) async {
        guard let roomAdminService else { return }

        // Set only the slots this view model owns. The admin service is shared
        // with the settings/CLI view model, so clearing here would drop its CLI
        // handler and silently break late CLI-response delivery.
        await roomAdminService.setStatusHandler { [weak self] status in
            await self?.handleStatusResponse(status)
        }

        await roomAdminService.setTelemetryHandler { [weak self] response in
            await self?.helper.handleTelemetryResponse(response)
        }
    }

    /// Clear every handler slot on the shared admin service. Only for true
    /// surface teardown (sheet dismiss); calling it on a segment switch would
    /// wipe the CLI handler the settings view model relies on.
    func cleanup(roomAdminService: RoomAdminService?) async {
        guard let roomAdminService else { return }
        await roomAdminService.clearHandlers()
    }

    /// Clear only this view model's status/telemetry handler slots, leaving the settings view
    /// model's CLI handler intact. For the merged admin surface's status-segment teardown.
    func clearStatusHandlers(roomAdminService: RoomAdminService?) async {
        guard let roomAdminService else { return }
        await roomAdminService.clearStatusHandlers()
    }

    // MARK: - Status

    func requestStatus(for session: RemoteNodeSessionDTO) async {
        guard let roomAdminService else { return }
        if helper.session == nil { helper.session = session }

        await helper.runRetryingSectionRequest(
            operationName: "status",
            setLoading: { self.helper.isLoadingStatus = $0 },
            setError: { self.helper.statusSectionError = $0 },
            operation: { [roomAdminService] timeout in
                try await roomAdminService.requestStatus(sessionID: session.id, timeout: timeout)
            },
            onSuccess: { await self.handleStatusResponse($0) }
        )
    }

    private func handleStatusResponse(_ response: RemoteNodeStatus) async {
        await helper.handleStatusResponse(
            response,
            postedCount: response.roomServerPostedCount,
            postPushCount: response.roomServerPostPushCount
        )
    }

    // MARK: - Telemetry

    func requestTelemetry(for session: RemoteNodeSessionDTO) async {
        guard let roomAdminService else { return }
        if helper.session == nil { helper.session = session }

        await helper.runRetryingSectionRequest(
            operationName: "telemetry",
            setLoading: { self.helper.isLoadingTelemetry = $0 },
            setError: { self.helper.telemetrySectionError = $0 },
            operation: { [roomAdminService] timeout in
                try await roomAdminService.requestTelemetry(sessionID: session.id, timeout: timeout)
            },
            onSuccess: { await self.helper.handleTelemetryResponse($0) }
        )
    }

    // MARK: - Room-Only Display

    var postsReceivedDisplay: String {
        guard let count = helper.status?.roomServerPostedCount else { return NodeStatusViewModel.emDash }
        return count.formatted()
    }

    var postsPushedDisplay: String {
        guard let count = helper.status?.roomServerPostPushCount else { return NodeStatusViewModel.emDash }
        return count.formatted()
    }
}
