import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
struct RetainedAdminPartitionSurface {
  let session: RemoteNodeSessionDTO
  let settings: NodeSettingsViewModel
  let cli: NodeCLIViewModel
  let requestStatus: @MainActor () async -> Void
  let canMakeCLISend: @MainActor () -> Bool
  private let cliObservation: CLIObservation

  static func make(
    harness: RetainedConversationPartitionHarness, radioID: UUID, role: RemoteNodeRole
  ) async throws -> RetainedAdminPartitionSurface {
    let appState = harness.environment.appState
    let cliObservation = CLIObservation()
    let session = RemoteNodeSessionDTO(
      radioID: radioID, publicKey: harness.room.publicKey, name: "Retained admin",
      role: role, isConnected: true, permissionLevel: .admin
    )
    try await harness.store.saveRemoteNodeSessionDTO(session)
    switch role {
    case .roomServer:
      let models = appState.remoteAdminWorkspaces.room(for: session)
      preloadMetadata(models.settings.helper)
      await models.settings.configure(
        roomAdminService: { appState.connectionSnapshot(for: radioID)?.services.roomAdminService }, session: session
      )
      models.status.configure(
        roomAdminService: { appState.connectionSnapshot(for: radioID)?.services.roomAdminService },
        contactService: { appState.connectionSnapshot(for: radioID)?.services.contactService },
        nodeSnapshotService: { appState.connectionSnapshot(for: radioID)?.services.nodeSnapshotService }
      )
      if let send = models.settings.makeNodeCLISendClosure(session: session) {
        models.cli.configure(sessionName: session.name, remoteTarget: .room, sendRawCommand: { command, timeout in
          try await cliObservation.send(command: command, timeout: timeout, using: send)
        })
      }
      return RetainedAdminPartitionSurface(
        session: session, settings: models.settings.helper, cli: models.cli,
        requestStatus: { await models.status.requestStatus(for: session) },
        canMakeCLISend: { models.settings.makeNodeCLISendClosure(session: session) != nil },
        cliObservation: cliObservation
      )
    case .repeater:
      let models = appState.remoteAdminWorkspaces.repeater(for: session)
      preloadMetadata(models.settings.helper)
      await models.settings.configure(
        repeaterAdminService: { appState.connectionSnapshot(for: radioID)?.services.repeaterAdminService }, session: session
      )
      models.status.configure(
        repeaterAdminService: { appState.connectionSnapshot(for: radioID)?.services.repeaterAdminService },
        contactService: { appState.connectionSnapshot(for: radioID)?.services.contactService },
        nodeSnapshotService: { appState.connectionSnapshot(for: radioID)?.services.nodeSnapshotService },
        deviceHashSize: { appState.connectionSnapshot(for: radioID)?.device.hashSize }
      )
      if let send = models.settings.makeNodeCLISendClosure(session: session) {
        models.cli.configure(sessionName: session.name, remoteTarget: .repeater, sendRawCommand: { command, timeout in
          try await cliObservation.send(command: command, timeout: timeout, using: send)
        })
      }
      return RetainedAdminPartitionSurface(
        session: session, settings: models.settings.helper, cli: models.cli,
        requestStatus: { await models.status.requestStatus(for: session) },
        canMakeCLISend: { models.settings.makeNodeCLISendClosure(session: session) != nil },
        cliObservation: cliObservation
      )
    case .sensor:
      preconditionFailure("Sensors use a separate settings workspace")
    }
  }

  func sendSettingsCommand() async -> Bool {
    do {
      _ = try await settings.sendAndWait(Self.command, rawMatching: true)
      return false
    } catch {
      return true
    }
  }

  func executeCLI(harness: RetainedConversationPartitionHarness) async throws {
    let completionsBefore = cliObservation.completedCount
    let failuresBefore = cliObservation.failedCount
    cli.executeCommand(Self.command)
    try await harness.waitUntil {
      !cli.isWaitingForResponse && cliObservation.completedCount > completionsBefore
    }
    let actualSendFailed = cliObservation.failedCount > failuresBefore
    #expect(actualSendFailed)
  }

  func close() {
    cli.cancelCurrentCommand()
  }

  private static func preloadMetadata(_ helper: NodeSettingsViewModel) {
    helper.firmwareVersion = "1.18.0"
    helper.applyDeviceTime("06:40 - 18/4/2025 UTC")
  }

  private static let command = "ver"

  @MainActor
  private final class CLIObservation {
    var completedCount = 0
    var failedCount = 0

    func send(
      command: String, timeout: Duration,
      using operation: @MainActor (String, Duration) async throws -> String
    ) async throws -> String {
      defer { completedCount += 1 }
      do {
        return try await operation(command, timeout)
      } catch {
        failedCount += 1
        throw error
      }
    }
  }
}
