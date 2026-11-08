import Foundation
import MeshCore

public extension RemoteNodeService {
  internal static let cliIgnoredSpace: UInt8 = 0x20

  // MARK: - CLI Commands

  /// Send a CLI command through an admin session.
  /// Legacy replies must match the expected response shape to avoid adopting stale responses.
  func sendCLICommand(
    sessionID: UUID,
    command: String,
    timeout: Duration = .seconds(10)
  ) async throws -> String {
    try await performCLICommand(
      sessionID: sessionID,
      command: command,
      timeout: timeout,
      acceptsAnyResponse: false
    )
  }

  /// Send a CLI command through an admin session, accepting free-form legacy replies.
  func sendRawCLICommand(
    sessionID: UUID,
    command: String,
    timeout: Duration = .seconds(10)
  ) async throws -> String {
    let response = try await performCLICommand(
      sessionID: sessionID,
      command: command,
      timeout: timeout,
      acceptsAnyResponse: true
    )

    // Clear stored password after admin password change
    await handlePasswordChangeIfNeeded(command: command, sessionID: sessionID)

    return response
  }

  /// Hold the destination's CLI slot through authorization and any Direct-to-Flood retry.
  private func performCLICommand(
    sessionID: UUID,
    command: String,
    timeout: Duration,
    acceptsAnyResponse: Bool
  ) async throws -> String {
    guard let remoteSession = try await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      throw RemoteNodeError.sessionNotFound
    }

    guard remoteSession.isAdmin else {
      throw RemoteNodeError.permissionDenied
    }

    let command = remoteSession.isSensor
      ? String(decoding: command.utf8.drop(while: { $0 == Self.cliIgnoredSpace }), as: UTF8.self)
      : RemoteCLICommandRewriter.rewrite(command)
    if remoteSession.isSensor { try SensorSetting.validateCommand(command) }

    await auditLogger.logCLICommand(
      publicKey: remoteSession.publicKey,
      command: command,
      target: CommandAuditLogger.target(for: remoteSession.role)
    )

    let destinationPrefix = Data(remoteSession.publicKey.prefix(Self.cliPublicKeyPrefixLength))
    let ownerID = UUID()

    try await acquireCLISlot(for: destinationPrefix, waiterID: ownerID)
    defer { releaseCLISlot(for: destinationPrefix, ownerID: ownerID) }
    try Task.checkCancellation()

    if remoteSession.isSensor {
      _ = try await requireSensorAdmin(sessionID: sessionID)
      try validateCLISlot(for: destinationPrefix, ownerID: ownerID)
      if RemoteCLINoReplyCommand.sensorCommand(command) != nil {
        do {
          _ = try await session.sendCommand(
            to: remoteSession.publicKey, command: makeCLIWirePrefix() + command, textType: .cliData
          )
        } catch is CancellationError {
          throw CancellationError()
        } catch let error as MeshCoreError {
          throw RemoteNodeError.sessionError(error)
        }
        await disconnect(sessionID: sessionID)
        return ""
      }
      return try await performCLIExchange(
        publicKey: remoteSession.publicKey, destinationPrefix: destinationPrefix,
        command: command, acceptsAnyResponse: true, timeout: timeout,
        ownerID: ownerID, textType: .cliData
      )
    }

    // A no-reply command is sent once. Path reset and resend would repeat it.
    if RemoteCLINoReplyCommand(command) != nil {
      return try await performCLIExchange(
        publicKey: remoteSession.publicKey,
        destinationPrefix: destinationPrefix,
        command: command,
        acceptsAnyResponse: acceptsAnyResponse,
        timeout: timeout,
        ownerID: ownerID,
        textType: .cliData
      )
    }

    return try await performWithDirectPathFloodRecovery(
      radioID: remoteSession.radioID,
      publicKey: remoteSession.publicKey,
      operationName: "remoteCLI"
    ) {
      try await self.performCLIExchange(
        publicKey: remoteSession.publicKey,
        destinationPrefix: destinationPrefix,
        command: command,
        acceptsAnyResponse: acceptsAnyResponse,
        timeout: timeout,
        ownerID: ownerID,
        textType: .cliData
      )
    }
  }

  /// Sends a Radio CLI command to a chat contact. The contact runs it only after granting this radio Remote CLI.
  /// A missing reply is a timeout, not a reason to reset the contact's direct path.
  func sendRadioCLICommand(
    to publicKey: Data,
    command: String,
    timeout: Duration
  ) async throws -> String {
    let destinationPrefix = Data(publicKey.prefix(Self.cliPublicKeyPrefixLength))
    let ownerID = UUID()
    await auditLogger.logCLICommand(publicKey: publicKey, command: command, target: .contact)

    try await acquireCLISlot(for: destinationPrefix, waiterID: ownerID)
    defer { releaseCLISlot(for: destinationPrefix, ownerID: ownerID) }
    try Task.checkCancellation()

    return try await performCLIExchange(
      publicKey: publicKey,
      destinationPrefix: destinationPrefix,
      command: command,
      acceptsAnyResponse: false,
      timeout: timeout,
      ownerID: ownerID,
      textType: .cliCommand
    )
  }

  private func handlePasswordChangeIfNeeded(command: String, sessionID: UUID) async {
    let lower = command.lowercased().trimmingCharacters(in: .whitespaces)

    // Only admin password changes, not guest
    guard lower.hasPrefix("password "), !lower.contains("guest.password") else {
      return
    }

    guard let session = try? await dataStore.fetchRemoteNodeSession(id: sessionID) else {
      return
    }

    do {
      try await keychainService.deletePassword(forNodeKey: session.publicKey)
      logger.info("Cleared stored password after password change for session \(sessionID)")
    } catch {
      logger.warning("Failed to clear stored password for session \(sessionID): \(error)")
      // Next login fails naturally - user re-enters password, overwrites stale credential
    }
  }
}
