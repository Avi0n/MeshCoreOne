import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Retained remote admin partition admission")
@MainActor
struct RetainedAdminPartitionTests {
  @Test(arguments: [RemoteNodeRole.roomServer, .repeater])
  func `retained admin entry points cannot acquire canonical B`(role: RemoteNodeRole) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let appState = harness.environment.appState
      await appState.wireServicesIfConnected()
      let retained = try await RetainedAdminPartitionSurface.make(
        harness: harness, radioID: harness.room.radioID, role: role
      )
      defer { retained.close() }
      let originallyConfigured = retained.canMakeCLISend()
      try #require(originallyConfigured)
      appState.navigation.navigateToRoom(with: harness.room)
      appState.navigation.navigateToContactDetail(harness.contact)
      try await harness.importCanonicalIdentity()
      await appState.wireServicesIfConnected()
      let perRadioNavigationCleared = appState.navigation.chatsSelectedRoute == nil
        && appState.navigation.selectedContact == nil
      #expect(perRadioNavigationCleared)
      let previousWorkspaceReset = switch role {
      case .roomServer: appState.remoteAdminWorkspaces.room(for: retained.session).settings.helper !== retained.settings
      case .repeater: appState.remoteAdminWorkspaces.repeater(for: retained.session).settings.helper !== retained.settings
      case .sensor: false
      }
      #expect(previousWorkspaceReset)
      let framesBefore = await outgoingAdminFrameCount(harness)
      await harness.radio.rejectNext(.sendStatusRequest)
      await harness.radio.rejectNext(.sendMessage, count: Self.commandRejections)

      await retained.requestStatus()
      let settingsRefused = await retained.sendSettingsCommand()
      try await retained.executeCLI(harness: harness)

      #expect(settingsRefused)
      let noForeignFrame = await outgoingAdminFrameCount(harness) == framesBefore
      #expect(noForeignFrame)
      let noFreshForeignCLI = !retained.canMakeCLISend()
      #expect(noFreshForeignCLI)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: [RemoteNodeRole.roomServer, .repeater])
  func `cached foreign admin is not rebound to canonical B`(role: RemoteNodeRole) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      let appState = harness.environment.appState
      try await harness.importCanonicalIdentity()
      await appState.wireServicesIfConnected()
      let retained = try await RetainedAdminPartitionSurface.make(
        harness: harness, radioID: harness.room.radioID, role: role
      )
      defer { retained.close() }
      let framesBefore = await outgoingAdminFrameCount(harness)
      await harness.radio.rejectNext(.sendStatusRequest)
      await harness.radio.rejectNext(.sendMessage, count: Self.commandRejections)

      await appState.remoteAdminWorkspaces.rebind(
        radioID: harness.canonicalRadioID,
        repeaterAdminService: { appState.services?.repeaterAdminService },
        roomAdminService: { appState.services?.roomAdminService },
        contactService: { appState.services?.contactService },
        nodeSnapshotService: { appState.services?.nodeSnapshotService },
        deviceHashSize: { appState.connectedDevice?.hashSize },
        remoteNodeService: { appState.connectionSnapshot(for: harness.canonicalRadioID)?.services.remoteNodeService },
        dataStore: { appState.offlineDataStore },
        connectionState: { appState.connectionState },
        isCurrent: { appState.connectionSnapshot(for: harness.canonicalRadioID) != nil }
      )
      await retained.requestStatus()
      let settingsRefused = await retained.sendSettingsCommand()

      #expect(settingsRefused)
      let noForeignFrame = await outgoingAdminFrameCount(harness) == framesBefore
      #expect(noForeignFrame)
      let noForeignCLI = !retained.canMakeCLISend()
      #expect(noForeignCLI)
      try await harness.proveLaterIO()
    }
  }

  @Test(arguments: [RemoteNodeRole.roomServer, .repeater])
  func `current admin entry points still use real wire`(role: RemoteNodeRole) async throws {
    try await RetainedConversationPartitionHarness.withHarness { harness in
      try await harness.importCanonicalIdentity()
      await harness.environment.appState.wireServicesIfConnected()
      let current = try await RetainedAdminPartitionSurface.make(
        harness: harness, radioID: harness.canonicalRadioID, role: role
      )
      defer { current.close() }
      let framesBefore = await outgoingAdminFrameCount(harness)
      await harness.radio.rejectNext(.sendStatusRequest)
      await harness.radio.rejectNext(.sendMessage, count: Self.commandRejections)

      await current.requestStatus()
      let settingsSettled = await current.sendSettingsCommand()
      try await current.executeCLI(harness: harness)

      #expect(settingsSettled)
      let threeActualCommands = await outgoingAdminFrameCount(harness) == framesBefore + Self.currentCommandCount
      #expect(threeActualCommands)
      try await harness.proveLaterIO()
    }
  }

  private func outgoingAdminFrameCount(_ harness: RetainedConversationPartitionHarness) async -> Int {
    await harness.radio.sentData.filter {
      $0.first == CommandCode.sendStatusRequest.rawValue || $0.first == CommandCode.sendMessage.rawValue
    }.count
  }

  private static let commandRejections = 2
  private static let currentCommandCount = 3
}
