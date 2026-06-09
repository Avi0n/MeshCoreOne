import OSLog
import MC1Services
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "NodeTelemetryVM")

@Observable
@MainActor
final class NodeTelemetryViewModel {

    // MARK: - Shared Helper

    var helper = NodeStatusHelper()

    // MARK: - Dependencies

    private var binaryProtocolService: BinaryProtocolService?
    private var publicKey: Data?

    // MARK: - Initialization

    func configure(appState: AppState, contact: ContactDTO) {
        self.binaryProtocolService = appState.services?.binaryProtocolService
        self.publicKey = contact.publicKey
        helper.configure(
            contactService: appState.services?.contactService,
            nodeSnapshotService: appState.services?.nodeSnapshotService
        )
        helper.configureForDirectTelemetry(publicKey: contact.publicKey)
    }

    // MARK: - Telemetry

    func requestTelemetry() async {
        guard let binaryProtocolService, let publicKey else { return }

        helper.isLoadingTelemetry = true
        helper.telemetrySectionError = nil

        do {
            let response = try await binaryProtocolService.requestTelemetry(from: publicKey)
            await helper.handleTelemetryResponse(response)
        } catch BinaryProtocolError.sessionError(MeshCoreError.timeout) {
            helper.telemetrySectionError = L10n.RemoteNodes.RemoteNodes.Status.telemetryTimedOut
            helper.isLoadingTelemetry = false
        } catch {
            helper.telemetrySectionError = error.localizedDescription
            helper.isLoadingTelemetry = false
        }
    }
}
