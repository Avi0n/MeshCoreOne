import Foundation
import MC1Services

/// Sensor, repeater, and room admin models, kept until the radio resets.
@MainActor
final class RemoteAdminWorkspaces {
  struct RepeaterModels {
    let settings: RepeaterSettingsViewModel
    let status: RepeaterStatusViewModel
    let cli: NodeCLIViewModel
    fileprivate let session: RemoteNodeSessionDTO
  }

  struct RoomModels {
    let settings: RoomSettingsViewModel
    let status: RoomStatusViewModel
    let cli: NodeCLIViewModel
    fileprivate let session: RemoteNodeSessionDTO
  }

  struct SensorModels {
    let settings: SensorSettingsViewModel
    let cli: NodeCLIViewModel
    fileprivate let session: RemoteNodeSessionDTO
  }

  private struct Key: Hashable {
    var radioID: UUID
    var publicKey: Data
  }

  private var repeaters: [Key: RepeaterModels] = [:]
  private var sensors: [Key: SensorModels] = [:]
  private var rooms: [Key: RoomModels] = [:]

  func repeater(for session: RemoteNodeSessionDTO) -> RepeaterModels {
    let key = Key(radioID: session.radioID, publicKey: session.publicKey)
    if let existing = repeaters[key] {
      return existing
    }
    let created = RepeaterModels(
      settings: RepeaterSettingsViewModel(),
      status: RepeaterStatusViewModel(),
      cli: NodeCLIViewModel(),
      session: session
    )
    repeaters[key] = created
    return created
  }

  func room(for session: RemoteNodeSessionDTO) -> RoomModels {
    let key = Key(radioID: session.radioID, publicKey: session.publicKey)
    if let existing = rooms[key] {
      return existing
    }
    let created = RoomModels(
      settings: RoomSettingsViewModel(),
      status: RoomStatusViewModel(),
      cli: NodeCLIViewModel(),
      session: session
    )
    rooms[key] = created
    return created
  }

  func sensor(for session: RemoteNodeSessionDTO) -> SensorModels {
    let key = Key(radioID: session.radioID, publicKey: session.publicKey)
    if let existing = sensors[key] { return existing }
    let created = SensorModels(settings: SensorSettingsViewModel(), cli: NodeCLIViewModel(), session: session)
    sensors[key] = created
    return created
  }

  @discardableResult
  func configureSensor(
    for session: RemoteNodeSessionDTO,
    remoteNodeService: @escaping @MainActor () -> RemoteNodeService?,
    dataStore: @escaping @MainActor () -> PersistenceStore?,
    connectionState: @escaping @MainActor () -> DeviceConnectionState
  ) -> SensorModels {
    let models = sensor(for: session)
    if !models.settings.isBound(to: remoteNodeService()) { models.cli.reset() }
    models.settings.configure(session: session, remoteNodeService: remoteNodeService,
                              dataStore: dataStore, connectionState: connectionState)
    models.cli.configure(sessionName: session.name, remoteTarget: .sensor) { [weak settings = models.settings] command, timeout in
      guard let settings else { throw RemoteNodeError.notConnected }
      return try await settings.sendRawCommand(command, timeout: timeout)
    }
    return models
  }

  func reset() {
    for models in repeaters.values {
      models.settings.reset()
      models.status.reset()
      models.cli.reset()
    }
    for models in rooms.values {
      models.settings.reset()
      models.status.reset()
      models.cli.reset()
    }
    for models in sensors.values {
      models.settings.reset()
      models.cli.reset()
    }
    sensors.removeAll()
    repeaters.removeAll()
    rooms.removeAll()
  }

  /// Same-device ready. Rebinds providers on the models that are still live.
  func rebind(
    repeaterAdminService: @escaping @MainActor () -> RepeaterAdminService?,
    roomAdminService: @escaping @MainActor () -> RoomAdminService?,
    contactService: @escaping @MainActor () -> ContactService?,
    nodeSnapshotService: @escaping @MainActor () -> NodeSnapshotService?,
    deviceHashSize: @escaping @MainActor () -> Int?,
    remoteNodeService: @escaping @MainActor () -> RemoteNodeService?,
    dataStore: @escaping @MainActor () -> PersistenceStore?,
    connectionState: @escaping @MainActor () -> DeviceConnectionState
  ) async {
    for models in sensors.values {
      configureSensor(for: models.session, remoteNodeService: remoteNodeService,
                      dataStore: dataStore, connectionState: connectionState)
    }
    for models in repeaters.values {
      await models.settings.configure(
        repeaterAdminService: repeaterAdminService,
        session: models.session
      )
      models.status.configure(
        repeaterAdminService: repeaterAdminService,
        contactService: contactService,
        nodeSnapshotService: nodeSnapshotService,
        deviceHashSize: deviceHashSize
      )
      await models.status.registerHandlers()
      if let send = models.settings.makeNodeCLISendClosure(session: models.session) {
        models.cli.configure(
          sessionName: models.session.name,
          remoteTarget: CLIRemoteTarget(role: models.session.role),
          sendRawCommand: send
        )
      }
    }
    for models in rooms.values {
      await models.settings.configure(
        roomAdminService: roomAdminService,
        session: models.session
      )
      models.status.configure(
        roomAdminService: roomAdminService,
        contactService: contactService,
        nodeSnapshotService: nodeSnapshotService
      )
      await models.status.registerHandlers()
      if let send = models.settings.makeNodeCLISendClosure(session: models.session) {
        models.cli.configure(
          sessionName: models.session.name,
          remoteTarget: CLIRemoteTarget(role: models.session.role),
          sendRawCommand: send
        )
      }
    }
  }
}
