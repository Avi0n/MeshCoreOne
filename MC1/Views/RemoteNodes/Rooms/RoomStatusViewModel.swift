import MC1Services
import SwiftUI

/// ViewModel for room server status display
@Observable
@MainActor
final class RoomStatusViewModel {
  // MARK: - Shared Helper

  var helper = NodeStatusViewModel()

  // MARK: - Dependencies

  private var roomAdminServiceProvider: @MainActor () -> RoomAdminService? = { nil }
  var roomAdminService: RoomAdminService? {
    roomAdminServiceProvider()
  }

  // MARK: - Initialization

  init() {}

  /// Nil services mirror a disconnected state; requests then no-op.
  func configure(
    roomAdminService: @escaping @MainActor () -> RoomAdminService?,
    contactService: @escaping @MainActor () -> ContactService?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?
  ) {
    roomAdminServiceProvider = roomAdminService
    helper.configure(
      contactService: contactService,
      nodeSnapshotService: nodeSnapshotService
    )
  }

  /// Reads the live service from the provider so a reconnect-minted instance
  /// is used at call time. Sets only the slots this view model owns; the admin
  /// service is shared with the settings/CLI view model, so clearing here would
  /// drop its CLI handler and silently break late CLI-response delivery.
  func registerHandlers() async {
    guard let roomAdminService else { return }

    await roomAdminService.setStatusHandler { [weak self] status in
      guard await self?.matchesSession(status.publicKeyPrefix) == true else { return }
      await self?.handleStatusResponse(status)
    }

    await roomAdminService.setTelemetryHandler { [weak self] response in
      guard await self?.matchesSession(response.publicKeyPrefix) == true else { return }
      await self?.helper.handleTelemetryResponse(response)
    }
  }

  /// A salvaged late response can arrive while a different node's screen is
  /// open; only the session this screen shows may consume it.
  private func matchesSession(_ publicKeyPrefix: Data) -> Bool {
    guard !publicKeyPrefix.isEmpty, let publicKey = helper.session?.publicKey else { return false }
    return publicKey.prefix(publicKeyPrefix.count) == publicKeyPrefix
  }

  /// Clear every handler slot on the shared admin service. Only for true
  /// surface teardown (sheet dismiss); calling it on a segment switch would
  /// wipe the CLI handler the settings view model relies on.
  func cleanup() async {
    guard let roomAdminService else { return }
    await roomAdminService.clearHandlers()
  }

  /// Clear only this view model's status/telemetry handler slots, leaving the settings view
  /// model's CLI handler intact. For the merged admin surface's status-segment teardown.
  func clearStatusHandlers() async {
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
