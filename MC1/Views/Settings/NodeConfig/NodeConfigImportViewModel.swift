import MC1Services
import OSLog
import SwiftUI

@Observable
@MainActor
final class NodeConfigImportViewModel {
  struct ImportSource: Equatable, Sendable {
    let generation: UUID
    let requestID: UUID
    let deviceID: UUID
    let radioID: UUID
    let name: String

    @MainActor
    init(_ snapshot: ConnectionSnapshot) {
      generation = snapshot.generation
      requestID = snapshot.requestID
      deviceID = snapshot.device.id
      radioID = snapshot.device.radioID
      name = snapshot.device.nodeName
    }
  }

  var state: NodeConfigImportState = .selectingFile
  var errorMessage: String?
  var showFilePicker = false
  // Alert dismissal can precede its action; apply and cancel consume the confirmation payload.
  var showConfirmation = false
  var sections = ConfigSections()
  var currentName: String?
  var currentRadio: MeshCoreNodeConfig.RadioSettings?
  var currentPosition: MeshCoreNodeConfig.PositionSettings?
  var importTask: Task<Void, Never>?
  var parseTask: Task<Void, Never>?
  var currentParseID: UUID?
  var previewTask: Task<Void, Never>?
  var connectionManager: ConnectionManager?
  let logger = Logger(subsystem: "com.mc1", category: "NodeConfigImportVM")

  private var nodeConfigServiceProvider: @MainActor () -> NodeConfigService? = { nil }
  private var settingsServiceProvider: @MainActor () -> SettingsService? = { nil }
  private var connectedDeviceProvider: @MainActor () -> DeviceDTO? = { nil }
  var nodeConfigService: NodeConfigService? {
    nodeConfigServiceProvider()
  }

  var settingsService: SettingsService? {
    settingsServiceProvider()
  }

  var connectedDevice: DeviceDTO? {
    connectedDeviceProvider()
  }

  func configure(
    nodeConfigService: @escaping @MainActor () -> NodeConfigService?,
    settingsService: @escaping @MainActor () -> SettingsService?,
    connectedDevice: @escaping @MainActor () -> DeviceDTO?,
    connectionManager: ConnectionManager
  ) {
    nodeConfigServiceProvider = nodeConfigService
    settingsServiceProvider = settingsService
    connectedDeviceProvider = connectedDevice
    self.connectionManager = connectionManager
  }
}
