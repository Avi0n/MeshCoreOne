import MC1Services
import SwiftUI

/// ViewModel for room server status display
@Observable
@MainActor
final class RoomStatusViewModel {

    // MARK: - Shared Helper

    var helper = NodeStatusHelper()

    // MARK: - Dependencies

    private var roomAdminService: RoomAdminService?

    // MARK: - Initialization

    init() {}

    func configure(appState: AppState) {
        self.roomAdminService = appState.services?.roomAdminService
        helper.configure(
            contactService: appState.services?.contactService,
            nodeSnapshotService: appState.services?.nodeSnapshotService
        )
    }

    func registerHandlers(appState: AppState) async {
        guard let roomAdminService = appState.services?.roomAdminService else { return }

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
    func cleanup(appState: AppState) async {
        guard let roomAdminService = appState.services?.roomAdminService else { return }
        await roomAdminService.clearHandlers()
    }

    /// Clear only this view model's status/telemetry handler slots, leaving the settings view
    /// model's CLI handler intact. For the merged admin surface's status-segment teardown.
    func clearStatusHandlers(appState: AppState) async {
        guard let roomAdminService = appState.services?.roomAdminService else { return }
        await roomAdminService.clearStatusHandlers()
    }

    // MARK: - Status

    func requestStatus(for session: RemoteNodeSessionDTO) async {
        guard let roomAdminService else { return }

        if helper.session == nil { helper.session = session }
        helper.isLoadingStatus = true
        helper.statusSectionError = nil

        do {
            let response = try await helper.performWithTransientRetries(operationName: "status") { [roomAdminService] timeout in
                return try await roomAdminService.requestStatus(sessionID: session.id, timeout: timeout)
            }
            await handleStatusResponse(response)
        } catch RemoteNodeError.timeout {
            helper.statusSectionError = L10n.RemoteNodes.RemoteNodes.Status.requestTimedOut
            helper.isLoadingStatus = false
        } catch {
            helper.statusSectionError = error.localizedDescription
            helper.isLoadingStatus = false
        }
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
        helper.isLoadingTelemetry = true
        helper.telemetrySectionError = nil

        do {
            let response = try await helper.performWithTransientRetries(operationName: "telemetry") { [roomAdminService] timeout in
                return try await roomAdminService.requestTelemetry(sessionID: session.id, timeout: timeout)
            }
            helper.handleTelemetryResponse(response)
        } catch RemoteNodeError.timeout {
            helper.telemetrySectionError = L10n.RemoteNodes.RemoteNodes.Status.requestTimedOut
            helper.isLoadingTelemetry = false
        } catch {
            helper.telemetrySectionError = error.localizedDescription
            helper.isLoadingTelemetry = false
        }
    }

    // MARK: - Room-Only Display

    var postsReceivedDisplay: String {
        guard let count = helper.status?.roomServerPostedCount else { return NodeStatusHelper.emDash }
        return count.formatted()
    }

    var postsPushedDisplay: String {
        guard let count = helper.status?.roomServerPostPushCount else { return NodeStatusHelper.emDash }
        return count.formatted()
    }
}
