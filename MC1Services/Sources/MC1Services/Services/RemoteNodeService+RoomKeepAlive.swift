import Foundation
import MeshCore

extension RemoteNodeService {
  func sendScheduledKeepAlive(sessionID: UUID, publicKey: Data) async throws {
    try Task.checkCancellation()
    guard let room = try await dataStore.fetchRemoteNodeSession(id: sessionID),
          room.publicKey == publicKey,
          room.isRoom,
          radioID == nil || radioID == room.radioID else {
      throw RemoteNodeError.sessionNotFound
    }
    guard let scope = try await dataStore.captureReceiveScope(radioID: room.radioID, link: receiveLifetime) else {
      throw CancellationError()
    }

    let key = room.textRulesID ?? room.id
    let needsRecovery: Bool
    if roomKeepAliveSchedules[key]?.recoverySubmitted.withLock({ $0 }) == true {
      needsRecovery = false
    } else {
      let preference = await textRulesPreference()
      let rulesAreOn = preference?.effective(
        for: .room,
        key: .init(radioID: room.radioID, conversationID: key),
        override: room.textRulesOverride
      ).isOn == true
      if rulesAreOn {
        needsRecovery = true
      } else {
        needsRecovery = await (try? dataStore.hasRecentlyBlockedMessages(radioID: room.radioID, conversationID: key)) == true
      }
    }

    try Task.checkCancellation()
    let schedule = keepAliveSchedule(for: room)
    if let recovery = schedule.recovery {
      try await recovery.value
      return
    }
    if schedule.refresh != nil { return }
    let recoverySubmitted = schedule.recoverySubmitted.withLock { $0 }
    if recoverySubmitted || !needsRecovery {
      try await transmitRoomKeepAlive(room, scope: scope, schedule: schedule, useBookmark: !recoverySubmitted)
      return
    }

    let recovery = Task {
      defer { roomKeepAliveSchedules[key]?.recovery = nil }
      try await transmitRoomKeepAlive(room, scope: scope, schedule: schedule, useBookmark: true, markRecovery: true)
    }
    roomKeepAliveSchedules[key]?.recovery = recovery
    try await recovery.value
  }

  func refreshRoom(_ room: RemoteNodeSessionDTO) async throws {
    guard room.isRoom else { throw RemoteNodeError.invalidResponse }
    guard radioID == nil || radioID == room.radioID else { throw RemoteNodeError.notConnected }
    guard let scope = try await dataStore.captureReceiveScope(radioID: room.radioID, link: receiveLifetime) else {
      throw CancellationError()
    }

    let key = room.textRulesID ?? room.id
    let schedule = keepAliveSchedule(for: room)
    if let refresh = schedule.refresh {
      try await refresh.value
      return
    }

    let refresh = Task {
      defer { roomKeepAliveSchedules[key]?.refresh = nil }
      // A distinct refresh reads the bookmark after an in-flight automatic rewind.
      _ = await schedule.recovery?.result
      try await transmitRoomKeepAlive(room, scope: scope, schedule: schedule, useBookmark: true)
    }
    roomKeepAliveSchedules[key]?.refresh = refresh
    try await refresh.value
  }

  private func keepAliveSchedule(for room: RemoteNodeSessionDTO) -> RoomKeepAliveSchedule {
    let key = room.textRulesID ?? room.id
    let schedule = roomKeepAliveSchedules[key] ?? RoomKeepAliveSchedule(sessionID: room.id)
    roomKeepAliveSchedules[key] = schedule
    return schedule
  }

  private func transmitRoomKeepAlive(
    _ room: RemoteNodeSessionDTO,
    scope: ReceiveScope,
    schedule: RoomKeepAliveSchedule,
    useBookmark: Bool,
    markRecovery: Bool = false
  ) async throws {
    await auditLogger.logKeepAlive(target: .room, publicKey: room.publicKey)
    do {
      _ = try await session.sendKeepAlive(to: room.publicKey, syncSince: { [dataStore] in
        guard scope.isCurrent, schedule.lifetime.isCurrent else { throw CancellationError() }
        guard let contact = try await dataStore.fetchContact(radioID: room.radioID, publicKey: room.publicKey) else {
          throw RemoteNodeError.contactNotFound
        }
        guard !contact.isFloodRouted else { throw RemoteNodeError.floodRouted }
        guard let current = try await dataStore.fetchRemoteNodeSession(id: room.id),
              current.radioID == room.radioID,
              current.textRulesID == room.textRulesID else {
          throw RemoteNodeError.sessionNotFound
        }
        return useBookmark ? current.lastSyncTimestamp : 0
      }, beforeSend: {
        guard scope.isCurrent, schedule.lifetime.isCurrent else { throw CancellationError() }
        if markRecovery {
          schedule.recoverySubmitted.withLock { $0 = true }
        }
      })
    } catch let error as MeshCoreError {
      if markRecovery, error.deviceErrorCode == .tableFull {
        schedule.recoverySubmitted.withLock { $0 = false }
      }
      throw RemoteNodeError.sessionError(error)
    }
  }
}
