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
      operation: { [binaryProtocolService, publicKey] _ in
        // BinaryProtocolService relies on the session's own timeout; it has no
        // timeout parameter, and reports a timeout as a wrapped session error,
        // so map that to the telemetry-specific "may be disabled" copy.
        do {
          return try await binaryProtocolService.requestTelemetry(from: publicKey)
        } catch BinaryProtocolError.sessionError(MeshCoreError.timeout) {
          throw RemoteNodeError.timeout
        }
      },
      onSuccess: { await self.helper.handleTelemetryResponse($0) }
    )

    // The shared NodeStatusViewModel renders a generic timed-out string; telemetry has a more
    // specific cause worth surfacing, so refine that one case.
    if helper.telemetrySectionError == L10n.RemoteNodes.RemoteNodes.Status.requestTimedOut {
      helper.telemetrySectionError = L10n.RemoteNodes.RemoteNodes.Status.telemetryTimedOut
    }
  }
}
