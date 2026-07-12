import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("AppBackupViewModel — export success handling")
@MainActor
struct AppBackupViewModelExportTests {
  private func makeViewModel() throws -> AppBackupViewModel {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(modelContainer: container)
    return AppBackupViewModel(connectionManager: manager)
  }

  private func sampleManifest(messages: Int = 3, contacts: Int = 2) -> BackupManifest {
    BackupManifest(contactCount: contacts, messageCount: messages)
  }

  @Test
  func `handleExportResult(.success) promotes pending to success`() throws {
    let vm = try makeViewModel()
    let manifest = sampleManifest()
    vm.exportState = .pending(AppBackupViewModel.PendingExport(
      data: Data(repeating: 0xAB, count: 128),
      manifest: manifest
    ))

    let saveURL = URL(fileURLWithPath: "/tmp/MC1-backup-2026-04-19.mc1backup")
    vm.handleExportResult(.success(saveURL))

    #expect(vm.pendingExport == nil)
    #expect(vm.exportSummary != nil)
    #expect(vm.exportSummary?.filename == "MC1-backup-2026-04-19.mc1backup")
    #expect(vm.exportSummary?.byteCount == 128)
    #expect(vm.exportSummary?.manifest.messageCount == 3)
    #expect(vm.exportSummary?.manifest.contactCount == 2)
    #expect(vm.errorMessage == nil)
  }

  @Test
  func `handleExportResult(.failure(userCancelled)) returns to idle without errorMessage`() throws {
    let vm = try makeViewModel()
    vm.exportState = .pending(AppBackupViewModel.PendingExport(
      data: Data([0x01]),
      manifest: sampleManifest()
    ))
    vm.handleExportResult(.failure(CocoaError(.userCancelled)))

    #expect(vm.pendingExport == nil)
    #expect(vm.exportSummary == nil)
    #expect(vm.errorMessage == nil)
  }

  @Test
  func `handleExportResult(.failure(other)) surfaces errorMessage`() throws {
    let vm = try makeViewModel()
    vm.exportState = .pending(AppBackupViewModel.PendingExport(
      data: Data([0x01]),
      manifest: sampleManifest()
    ))
    let saveError = NSError(domain: NSPOSIXErrorDomain, code: 28) // ENOSPC

    vm.handleExportResult(.failure(saveError))

    #expect(vm.pendingExport == nil)
    #expect(vm.exportSummary == nil)
    #expect(vm.errorMessage != nil)
  }

  @Test
  func `handleExportResult(.success) with no pending export is a no-op`() throws {
    let vm = try makeViewModel()
    #expect(vm.pendingExport == nil)

    vm.handleExportResult(.success(URL(fileURLWithPath: "/tmp/x.mc1backup")))

    #expect(vm.exportSummary == nil)
    #expect(vm.errorMessage == nil)
  }

  @Test
  func `dismissExportSuccess clears the summary`() throws {
    let vm = try makeViewModel()
    vm.exportState = .success(AppBackupViewModel.ExportSuccessSummary(
      filename: "x.mc1backup",
      byteCount: 1,
      manifest: sampleManifest()
    ))

    vm.dismissExportSuccess()

    #expect(vm.exportSummary == nil)
  }
}

@Suite("App Backup")
@MainActor
struct AppBackupViewModelTests {
  @Test
  func `Import picker cancellation does not surface an error`() throws {
    let viewModel = try makeViewModel()

    viewModel.handleFileSelected(result: .failure(CocoaError(.userCancelled)))

    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `Readable backup URLs load even when no security scope is granted`() async throws {
    let viewModel = try makeViewModel()
    let backupURL = try makeReadableBackupURL()

    viewModel.loadAndParseBackup(from: backupURL)

    try await waitUntil("Backup preview never loaded") {
      viewModel.previewEnvelope != nil || viewModel.errorMessage != nil
    }

    let previewEnvelope = try #require(viewModel.previewEnvelope)
    #expect(viewModel.errorMessage == nil)
    #expect(previewEnvelope.manifest == BackupManifest())
  }

  @Test
  func `Export uses the active services store when one is available`() async throws {
    let manager = try ConnectionManager(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let radioID = UUID()
    let services = try ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      radioID: radioID
    )
    try await services.dataStore.saveContact(
      ContactDTO(
        id: UUID(),
        radioID: radioID,
        publicKey: Data(repeating: 0xAB, count: 32),
        name: "Backed Up Contact",
        typeRawValue: ContactType.chat.rawValue,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        lastAdvertTimestamp: 0,
        latitude: 0,
        longitude: 0,
        lastModified: 0,
        nickname: nil,
        isBlocked: false,
        isMuted: false,
        isFavorite: false,
        lastMessageDate: nil,
        unreadCount: 0
      )
    )
    manager.setTestState(services: services)

    let viewModel = AppBackupViewModel(connectionManager: manager)
    viewModel.performExport()

    try await waitUntil("Backup export never completed") {
      viewModel.pendingExport != nil || viewModel.errorMessage != nil
    }

    let pending = try #require(viewModel.pendingExport)
    let envelope = try parseBackup(data: pending.data)
    #expect(viewModel.errorMessage == nil)
    #expect(envelope.contacts.count == 1)
    #expect(envelope.contacts.first?.name == "Backed Up Contact")
  }

  @Test
  func `performImport aborts and dismisses when the radio is connected`() throws {
    // The VM's `connectionManager` is private, so drive connection state on the manager
    // before constructing the VM, using the existing test seam.
    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(modelContainer: container)
    manager.setTestState(connectionState: .ready)
    let vm = AppBackupViewModel(connectionManager: manager)
    let envelope = AppBackupEnvelope(appVersion: "test", appBuild: "1", manifest: BackupManifest())
    vm.importState = .preview(envelope)
    vm.performImport()
    // Aborted: no import ran and the sheet was dismissed back to idle.
    #expect(vm.previewEnvelope == nil)
    if case .importing = vm.importState { Issue.record("import ran while connected") }
  }

  private func makeViewModel() throws -> AppBackupViewModel {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let manager = ConnectionManager(modelContainer: container)
    return AppBackupViewModel(connectionManager: manager)
  }

  private func makeReadableBackupURL() throws -> URL {
    let envelope = AppBackupEnvelope(
      appVersion: "test",
      appBuild: "1",
      manifest: BackupManifest()
    )
    let jsonData = try makeBackupJSONEncoder().encode(envelope)
    let compressed = try jsonData.zlibCompressed()
    let encodedData = compressed.base64EncodedString()
    return try #require(URL(string: "data:application/octet-stream;base64,\(encodedData)"))
  }
}
