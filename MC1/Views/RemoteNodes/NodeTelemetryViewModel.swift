import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "NodeTelemetryVM")

@Observable
@MainActor
final class NodeTelemetryViewModel {
  // MARK: - Shared Helper

  var helper = NodeStatusViewModel()

  // MARK: - Dependencies

  private var binaryProtocolServiceProvider: @MainActor () -> BinaryProtocolService? = { nil }
  var binaryProtocolService: BinaryProtocolService? {
    binaryProtocolServiceProvider()
  }

  private var publicKey: Data?

  // MARK: - Initialization

  /// Nil services mirror a disconnected state; requests then no-op.
  func configure(
    binaryProtocolService: @escaping @MainActor () -> BinaryProtocolService?,
    contactService: @escaping @MainActor () -> ContactService?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?,
    contact: ContactDTO
  ) {
    binaryProtocolServiceProvider = binaryProtocolService
    publicKey = contact.publicKey
    helper.configure(
      contactService: contactService,
      nodeSnapshotService: nodeSnapshotService
    )
    helper.configureForDirectTelemetry(publicKey: contact.publicKey)
  }

  // MARK: - Telemetry

  func requestTelemetry() async {
    guard let binaryProtocolService, let publicKey else { return }

    await helper.runRetryingSectionRequest(
      operationName: "telemetry",
      setLoading: { self.helper.isLoadingTelemetry = $0 },
      setError: { self.helper.telemetrySectionError = $0 },
      timeoutMessage: L10n.RemoteNodes.RemoteNodes.Status.telemetryTimedOut,
      operation: { [binaryProtocolService, publicKey] _ in
        // Map session timeout so the section shows telemetry-specific copy.
        do {
          return try await binaryProtocolService.requestTelemetry(from: publicKey)
        } catch BinaryProtocolError.sessionError(MeshCoreError.timeout) {
          throw RemoteNodeError.timeout
        }
      },
      onSuccess: { await self.helper.handleTelemetryResponse($0) }
    )
  }
}
