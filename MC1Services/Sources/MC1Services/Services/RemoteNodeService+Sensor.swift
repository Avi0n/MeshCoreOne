import Foundation
import MeshCore

public extension RemoteNodeService {
  private static let sensorListCommand = "sensor list"
  private static let sensorGetPrefix = "sensor get "
  private static let sensorRebootCommand = "reboot"

  func connectSensor(
    radioID: UUID,
    contact: ContactDTO,
    password: String?,
    rememberPassword: Bool,
    requireAdmin: Bool,
    pathLength: UInt8,
    onTimeoutKnown: (@Sendable (Int) async -> Void)?
  ) async throws -> RemoteNodeSessionDTO {
    guard contact.type == .sensor, radioID == contact.radioID else { throw RemoteNodeError.invalidResponse }
    try Task.checkCancellation()
    let attemptID = UUID()
    sensorConnectAttempts[contact.id] = attemptID
    defer {
      if sensorConnectAttempts[contact.id] == attemptID { sensorConnectAttempts[contact.id] = nil }
    }
    let created = try await createSession(radioID: radioID, contact: contact, sensorAttemptID: attemptID)
    do {
      guard sensorConnectAttempts[contact.id] == attemptID, !isRetired else { throw RemoteNodeError.cancelled }
      let result = try await login(
        sessionID: created.id, password: password, pathLength: pathLength, onTimeoutKnown: onTimeoutKnown
      )
      try Task.checkCancellation()
      guard sensorConnectAttempts[contact.id] == attemptID, !isRetired else { throw RemoteNodeError.cancelled }
      guard !requireAdmin || result.isAdmin else { throw RemoteNodeError.permissionDenied }
      guard let updated = try await dataStore.fetchRemoteNodeSession(id: created.id),
            updated.isSensor, updated.isConnected else { throw RemoteNodeError.notConnected }
      try Task.checkCancellation()
      guard sensorConnectAttempts[contact.id] == attemptID, !isRetired else { throw RemoteNodeError.cancelled }
      if rememberPassword, let password {
        try await keychainService.storePassword(password, forNodeKey: contact.publicKey)
      } else if !rememberPassword {
        try await keychainService.deletePassword(forNodeKey: contact.publicKey)
      }
      try Task.checkCancellation()
      guard sensorConnectAttempts[contact.id] == attemptID, !isRetired else { throw RemoteNodeError.cancelled }
      return updated
    } catch {
      // Only this attempt may retire its session; an older cancellation cannot disconnect a newer login.
      if sensorConnectAttempts[contact.id] == attemptID { await disconnect(sessionID: created.id) }
      throw error
    }
  }

  func fetchSensorSettings(sessionID: UUID) async throws -> [SensorSetting] {
    _ = try await requireSensorAdmin(sessionID: sessionID)
    var settings: [SensorSetting] = []
    var keys: Set<String> = []
    var expectedTotal: Int?
    var offset = 0
    while true {
      try Task.checkCancellation()
      let command = offset == 0 ? Self.sensorListCommand : "\(Self.sensorListCommand) \(offset)"
      let response = try await sendRawCLICommand(sessionID: sessionID, command: command)
      let page = try SensorSettingsResponseParser.list(response, offset: offset)
      guard expectedTotal == nil || expectedTotal == page.total else { throw RemoteNodeError.invalidResponse }
      expectedTotal = page.total
      for setting in page.settings {
        guard keys.insert(setting.key).inserted else { throw RemoteNodeError.invalidResponse }
        settings.append(setting)
      }
      guard let next = page.next else { return settings }
      offset = next
    }
  }

  func setSensorSetting(sessionID: UUID, setting: SensorSetting, value: String) async throws -> SensorSetting {
    let command = try setting.command(setting: value)
    _ = try await requireSensorAdmin(sessionID: sessionID)
    let response: String
    do {
      response = try await sendRawCLICommand(sessionID: sessionID, command: command)
    } catch RemoteNodeError.timeout {
      throw RemoteNodeError.sensorOutcomeUnknown
    } catch RemoteNodeError.sessionError {
      throw RemoteNodeError.sensorOutcomeUnknown
    }
    do {
      try SensorSettingsResponseParser.accepted(response)
    } catch RemoteNodeError.sensorSettingRejected {
      throw RemoteNodeError.sensorSettingRejected
    } catch {
      throw RemoteNodeError.sensorOutcomeUnknown
    }
    do {
      let verified = try await sendRawCLICommand(sessionID: sessionID, command: Self.sensorGetPrefix + setting.key)
      guard try SensorSettingsResponseParser.value(verified) == value else { throw RemoteNodeError.sensorOutcomeUnknown }
      return SensorSetting(key: setting.key, value: value)
    } catch is CancellationError {
      throw CancellationError()
    } catch {
      throw RemoteNodeError.sensorOutcomeUnknown
    }
  }

  func rebootSensor(sessionID: UUID) async throws {
    _ = try await sendRawCLICommand(sessionID: sessionID, command: Self.sensorRebootCommand)
  }
}

extension RemoteNodeService {
  func requireSensorAdmin(sessionID: UUID) async throws -> RemoteNodeSessionDTO {
    try Task.checkCancellation()
    guard !isRetired else { throw RemoteNodeError.notConnected }
    guard let current = try await dataStore.fetchRemoteNodeSession(id: sessionID), current.isSensor else {
      throw RemoteNodeError.sessionNotFound
    }
    try Task.checkCancellation()
    guard current.isConnected else { throw RemoteNodeError.notConnected }
    guard current.isAdmin else { throw RemoteNodeError.permissionDenied }
    return current
  }
}
