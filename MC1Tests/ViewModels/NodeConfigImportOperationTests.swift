import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Node config import operation ownership", .serialized)
@MainActor
struct NodeConfigImportOperationTests {
  @Test(arguments: PreviewReplacement.allCases, PreviewSettlement.allCases)
  func `a retired preview cannot publish confirmation or failure`(
    replacement: PreviewReplacement, settlement: PreviewSettlement
  ) async throws {
    try await withImporter { env, model in
      try await selectFile(Self.originalConfig, model: model)
      await env.bluetooth.holdReplies(for: .deviceQuery)
      if settlement == .failure { await env.bluetooth.rejectNext(.deviceQuery) }
      model.prepareConfirmation()
      let preview = model.previewTask
      try await waitUntil("the preview should hold its capability read") {
        await env.bluetooth.heldReplyCount(for: .deviceQuery) == 1
      }
      try #require(model.isPreparingConfirmation)

      switch replacement {
      case .file:
        try await selectFile(Self.replacementConfig, model: model)
      case .dismissal:
        model.handleDismissal()
      }
      await env.bluetooth.releaseReplies(for: .deviceQuery)
      await preview?.value

      #expect(!model.isPreparingConfirmation)
      #expect(!model.showConfirmation)
      #expect(model.confirmation == nil)
      #expect(model.errorMessage == nil)
      model.applyConfig()
      #expect(!model.isApplying)
      #expect(await positionWriteCount(env.bluetooth) == 0)
      switch replacement {
      case .file:
        #expect(model.importedConfig == Self.replacementConfig)
        try await prepareConfirmation(model)
        #expect(model.showConfirmation)
      case .dismissal:
        #expect(model.importedConfig == nil)
        #expect(model.inputSource == nil)
      }
    }
  }

  @Test(arguments: ConfirmationChange.allCases)
  func `normal confirmation cannot authorize changed input sections or connection`(
    change: ConfirmationChange
  ) async throws {
    try await withImporter { env, model in
      try await selectFile(Self.originalConfig, model: model)
      try await prepareConfirmation(model)
      let source = try #require(env.manager.currentConnection)

      switch change {
      case .sections:
        model.sections.positionSettings = false
      case .file:
        try await selectFile(Self.replacementConfig, model: model)
      case .connection:
        await env.startDisconnect().value
        await env.bluetooth.setConnectError(nil)
        _ = try await env.appState.connectViaBluetooth(peripheralID: env.device.id)
        let replacement = try #require(env.manager.currentConnection)
        #expect(replacement.device.id == source.device.id)
        #expect(replacement.device.radioID == source.device.radioID)
        #expect(replacement.generation != source.generation)
        #expect(replacement.requestID != source.requestID)
      }

      model.applyConfig()

      #expect(!model.isApplying)
      #expect(!model.importComplete)
      #expect(!model.showConfirmation)
      #expect(await positionWriteCount(env.bluetooth) == 0)
    }
  }

  @Test
  func `confirmation presentation can dismiss before its apply action`() async throws {
    try await withImporter { env, model in
      try await selectFile(Self.originalConfig, model: model)
      try await prepareConfirmation(model)

      model.showConfirmation = false
      model.applyConfig()

      try await waitUntil("the confirmed import should complete") { !model.isApplying }
      #expect(model.importComplete)
      #expect(model.errorMessage == nil)
      #expect(await positionWriteCount(env.bluetooth) == 1)
      let selectedPositionWasWritten = await env.bluetooth.sentData.contains(
        PacketBuilder.setCoordinates(latitude: 37.5, longitude: -122.5)
      )
      #expect(selectedPositionWasWritten)
    }
  }

  @Test
  func `success cleanup preserves a subsequently selected file`() async throws {
    try await withImporter { env, model in
      try await selectFile(Self.originalConfig, model: model)
      try await prepareConfirmation(model)
      model.applyConfig()
      let completedImport = model.importTask
      try await waitUntil("the import should display its completed result") { !model.isApplying }
      try #require(model.importComplete)
      try #require(model.errorMessage == nil)
      #expect(await positionWriteCount(env.bluetooth) == 1)

      try await selectFile(Self.replacementConfig, model: model)
      let replacementID = model.inputID
      let replacementSource = model.inputSource
      await completedImport?.value

      #expect(model.importedConfig == Self.replacementConfig)
      #expect(model.inputID == replacementID)
      #expect(model.inputSource == replacementSource)
      #expect(model.sections.positionSettings)
      #expect(!model.isApplying)
      #expect(!model.importComplete)
      #expect(model.errorMessage == nil)
      #expect(await positionWriteCount(env.bluetooth) == 1)
    }
  }

  private func withImporter(
    body: (ConnectionLinkSwitchHarness, NodeConfigImportViewModel) async throws -> Void
  ) async throws {
    let env = try await ConnectionLinkSwitchHarness.make()
    let model = NodeConfigImportViewModel()
    model.configure(
      nodeConfigService: { env.manager.services?.nodeConfigService },
      settingsService: { env.manager.services?.settingsService },
      connectedDevice: { env.manager.connectedDevice }, connectionManager: env.manager
    )
    var bodyError: (any Error)?
    do {
      model.beginImport()
      try await body(env, model)
    } catch {
      bodyError = error
    }
    model.cancelImport()
    model.parseTask?.cancel()
    model.previewTask?.cancel()
    await env.bluetooth.releaseAllGates()
    await env.wifi.releaseAllGates()
    await model.importTask?.value
    await model.parseTask?.value
    await model.previewTask?.value
    await env.close()
    if let bodyError { throw bodyError }
  }

  private func selectFile(_ config: MeshCoreNodeConfig, model: NodeConfigImportViewModel) async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("config.json")
    try JSONEncoder().encode(config).write(to: url)
    model.parseFile(at: url)
    await model.parseTask?.value
    try #require(model.importedConfig == config)
    try #require(!model.isParsing)
  }

  private func prepareConfirmation(_ model: NodeConfigImportViewModel) async throws {
    model.prepareConfirmation()
    await model.previewTask?.value
    try #require(model.showConfirmation)
    try #require(!model.isPreparingConfirmation)
    try #require(model.errorMessage == nil)
  }

  private func positionWriteCount(_ transport: ConnectionTestRadioTransport) async -> Int {
    await transport.sentData.filter { $0.first == CommandCode.setCoordinates.rawValue }.count
  }

  enum PreviewReplacement: CaseIterable, Sendable { case file, dismissal }
  enum PreviewSettlement: CaseIterable, Sendable { case success, failure }
  enum ConfirmationChange: CaseIterable, Sendable { case sections, file, connection }

  private static let originalConfig = MeshCoreNodeConfig(
    positionSettings: .init(latitude: "37.5", longitude: "-122.5")
  )
  private static let replacementConfig = MeshCoreNodeConfig(
    positionSettings: .init(latitude: "38.5", longitude: "-121.5")
  )
}
