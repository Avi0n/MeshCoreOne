import Foundation
import MC1Services
import OSLog

// MARK: - Session and Login Management

extension CLIToolViewModel {
  func handleSessionCommand(_ args: String) {
    let subcommand = args.trimmingCharacters(in: .whitespaces).lowercased()

    if subcommand.isEmpty || subcommand == "list" {
      showSessionList()
    } else if subcommand == "local" {
      switchToLocal()
    } else {
      switchToSession(named: args.trimmingCharacters(in: .whitespaces))
    }
  }

  func showSessionList() {
    appendOutput(L10n.Tools.Tools.Cli.sessionListHeader, type: .response)

    let localMarker = (activeSession?.isLocal == true) ? "*" : " "
    appendOutput("  \(localMarker) 1. \(localDeviceName) (\(L10n.Tools.Tools.Cli.sessionLocal))", type: .response)

    for (index, session) in remoteSessions.enumerated() {
      let marker = (activeSession?.id == session.id) ? "*" : " "
      appendOutput("  \(marker) \(index + 2). @\(session.name)", type: .response)
    }
  }

  func switchToLocal() {
    clearCompletionState()
    activeSession = .local(deviceName: localDeviceName)
    appendOutput("\(L10n.Tools.Tools.Cli.sessionSwitched) \(localDeviceName)", type: .success)
  }

  func switchToSession(named name: String) {
    clearCompletionState()
    // Check if input is a number
    if let number = Int(name) {
      if number == 1 {
        switchToLocal()
        return
      }
      let remoteIndex = number - 2
      if remoteIndex >= 0, remoteIndex < remoteSessions.count {
        let session = remoteSessions[remoteIndex]
        activeSession = session
        appendOutput("\(L10n.Tools.Tools.Cli.sessionSwitched) @\(session.name)", type: .success)
        return
      }
      appendOutput("\(L10n.Tools.Tools.Cli.sessionNotFound) \(name)", type: .error)
      return
    }

    // Match by name
    let predicate: (CLISession) -> Bool = { $0.name.localizedCaseInsensitiveCompare(name) == .orderedSame }
    if let session = remoteSessions.first(where: predicate) {
      activeSession = session
      appendOutput("\(L10n.Tools.Tools.Cli.sessionSwitched) @\(session.name)", type: .success)
    } else {
      appendOutput("\(L10n.Tools.Tools.Cli.sessionNotFound) \(name)", type: .error)
    }
  }

  func handleLogin(_ args: String) async {
    guard activeSession?.isLocal == true else {
      appendOutput(L10n.Tools.Tools.Cli.loginFromLocalOnly, type: .error)
      return
    }

    // Parse --forget or -f flag (must be first argument)
    let trimmed = args.trimmingCharacters(in: .whitespaces)
    let forgetPassword: Bool
    let nodeName: String

    if trimmed.hasPrefix("--forget ") {
      forgetPassword = true
      nodeName = String(trimmed.dropFirst("--forget ".count)).trimmingCharacters(in: .whitespaces)
    } else if trimmed.hasPrefix("-f ") {
      forgetPassword = true
      nodeName = String(trimmed.dropFirst("-f ".count)).trimmingCharacters(in: .whitespaces)
    } else {
      forgetPassword = false
      nodeName = trimmed
    }

    guard !nodeName.isEmpty else {
      appendOutput(L10n.Tools.Tools.Cli.loginUsage, type: .error)
      return
    }

    guard let dataStore, let radioID, let remoteNodeService else {
      appendOutput(L10n.Tools.Tools.Cli.notConnected, type: .error)
      return
    }

    // Find contact by name (repeaters and room servers only)
    let contact: ContactDTO
    do {
      let contacts = try await dataStore.fetchContacts(radioID: radioID)
      guard let found = contacts.first(where: {
        $0.name.localizedCaseInsensitiveCompare(nodeName) == .orderedSame
          && ($0.type == .repeater || $0.type == .room)
      }) else {
        appendOutput("\(L10n.Tools.Tools.Cli.nodeNotFound) \(nodeName)", type: .error)
        return
      }
      contact = found
    } catch {
      Self.logger.error("Failed to fetch contacts: \(error)")
      appendOutput("\(L10n.Tools.Tools.Cli.nodeNotFound) \(nodeName)", type: .error)
      return
    }

    // Clear saved password if --forget flag was used
    if forgetPassword {
      try? await remoteNodeService.deletePassword(forContact: contact)
    }

    // Check for stored password (will be nil if --forget was used)
    if let storedPassword = await remoteNodeService.retrievePassword(forContact: contact) {
      await completeLogin(contact: contact, password: storedPassword)
    } else {
      // Prompt for password
      pendingLoginContact = contact
    }
  }

  func completeLogin(contact: ContactDTO, password: String) async {
    guard let radioID, let remoteNodeService else {
      appendOutput(L10n.Tools.Tools.Cli.notConnected, type: .error)
      return
    }

    defer { stopCountdown() }

    do {
      // Create or reuse session
      let remoteSession = try await remoteNodeService.createSession(
        radioID: radioID,
        contact: contact
      )

      guard !Task.isCancelled else { return }

      // Login with countdown
      let loginResult = try await remoteNodeService.login(
        sessionID: remoteSession.id,
        password: password,
        pathLength: contact.outPathLength,
        onTimeoutKnown: { [weak self] seconds in
          await self?.startCountdown(seconds)
        }
      )

      guard !Task.isCancelled else { return }

      guard loginResult.success else {
        appendOutput(
          "\(L10n.Tools.Tools.Cli.loginFailed) \(L10n.Tools.Tools.Cli.loginFailedAuth)",
          type: .error
        )
        return
      }

      // Store password after successful login
      try? await remoteNodeService.storePassword(password, forNodeKey: contact.publicKey)

      // Success - create CLI session
      let cliSession = CLISession.remote(
        id: remoteSession.id,
        name: contact.name,
        pathLength: contact.outPathLength
      )
      remoteSessions.append(cliSession)
      activeSession = cliSession
      appendOutput("\(L10n.Tools.Tools.Cli.loginSuccess) @\(contact.name)", type: .success)

    } catch let error as RemoteNodeError {
      switch error {
      case .passwordNotFound:
        appendOutput(L10n.Tools.Tools.Cli.passwordRequired, type: .error)
      case .timeout:
        appendOutput(L10n.Tools.Tools.Cli.timeout, type: .error)
      case let .loginFailed(reason):
        appendOutput("\(L10n.Tools.Tools.Cli.loginFailed) \(reason)", type: .error)
      case .cancelled:
        appendOutput(L10n.Tools.Tools.Cli.cancelled, type: .error)
      default:
        appendOutput("\(L10n.Tools.Tools.Cli.loginFailed) \(error.localizedDescription)", type: .error)
      }
    } catch is CancellationError {
      // Already handled by cancelCurrentCommand
    } catch {
      appendOutput("\(L10n.Tools.Tools.Cli.loginFailed) \(error.localizedDescription)", type: .error)
    }
  }

  func handleLogout() async {
    guard let session = activeSession, !session.isLocal else {
      appendOutput(L10n.Tools.Tools.Cli.notLoggedIn, type: .error)
      return
    }

    // Logout via RemoteNodeService (errors ignored per protocol design)
    if let remoteNodeService {
      try? await remoteNodeService.logout(sessionID: session.id)
    }

    remoteSessions.removeAll { $0.id == session.id }
    activeSession = .local(deviceName: localDeviceName)
    appendOutput(L10n.Tools.Tools.Cli.logoutSuccess, type: .success)
  }

  // MARK: - Remote Commands

  func sendRemoteCommand(_ command: String) async {
    guard let session = activeSession,
          !session.isLocal,
          let service = repeaterAdminService else {
      appendOutput(L10n.Tools.Tools.Cli.notLoggedIn, type: .error)
      return
    }

    // Reboot won't return a response - treat timeout as success
    if command.lowercased().hasPrefix("reboot") {
      do {
        _ = try await service.sendRawCommand(
          sessionID: session.id,
          command: command,
          timeout: RemoteOperationTimeoutPolicy.fireAndForgetCLI
        )
        appendOutput(L10n.Tools.Tools.Cli.rebootSent, type: .success)
      } catch RemoteNodeError.timeout {
        appendOutput(L10n.Tools.Tools.Cli.rebootSent, type: .success)
      } catch {
        appendOutput(error.localizedDescription, type: .error)
      }
      return
    }

    do {
      let response = try await service.sendRawCommand(sessionID: session.id, command: command)

      guard !Task.isCancelled else { return }

      appendOutput(response, type: .response)
    } catch is CancellationError {
      // Already handled by cancelCurrentCommand
    } catch let error as RemoteNodeError {
      if case .timeout = error {
        appendOutput(L10n.Tools.Tools.Cli.commandTimeout, type: .error)
      } else {
        appendOutput("\(error.localizedDescription)", type: .error)
      }
    } catch {
      appendOutput("\(error.localizedDescription)", type: .error)
    }
  }

  // MARK: - Local Command Handlers

  func handleNodesCommand() async {
    guard let dataStore, let radioID else {
      appendOutput(L10n.Tools.Tools.Cli.notConnected, type: .error)
      return
    }

    let contacts: [ContactDTO]
    do {
      contacts = try await dataStore.fetchContacts(radioID: radioID)
    } catch {
      Self.logger.error("Failed to fetch contacts: \(error)")
      appendOutput(L10n.Tools.Tools.Cli.noNodes, type: .response)
      return
    }

    let nodes = contacts
      .filter { $0.type == .repeater || $0.type == .room }
      .sorted { lhs, rhs in
        if lhs.type != rhs.type {
          return lhs.type == .repeater
        }
        return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
      }

    guard !nodes.isEmpty else {
      appendOutput(L10n.Tools.Tools.Cli.noNodes, type: .response)
      return
    }

    appendOutput(L10n.Tools.Tools.Cli.nodesHeader(nodes.count), type: .response)
    for node in nodes {
      let typeLabel = node.type == .repeater
        ? L10n.Contacts.Contacts.NodeKind.repeater
        : L10n.Contacts.Contacts.NodeKind.room
      let route = formatRoute(node)
      let padded = node.name.padding(toLength: 20, withPad: " ", startingAt: 0)
      let label = typeLabel.padding(toLength: 10, withPad: " ", startingAt: 0)
      appendOutput("  \(padded) \(label)\(route)", type: .response)
    }
  }

  func handleChannelsCommand() async {
    guard let dataStore, let radioID else {
      appendOutput(L10n.Tools.Tools.Cli.notConnected, type: .error)
      return
    }

    let channels: [ChannelDTO]
    do {
      channels = try await dataStore.fetchChannels(radioID: radioID)
    } catch {
      Self.logger.error("Failed to fetch channels: \(error)")
      appendOutput(L10n.Tools.Tools.Cli.noChannels, type: .response)
      return
    }

    guard !channels.isEmpty else {
      appendOutput(L10n.Tools.Tools.Cli.noChannels, type: .response)
      return
    }

    let sorted = channels.sorted { $0.index < $1.index }

    appendOutput(L10n.Tools.Tools.Cli.channelsHeader(sorted.count), type: .response)
    for channel in sorted {
      let name = channel.name.isEmpty ? "(\(L10n.Tools.Tools.Cli.channelEmpty))" : channel.name
      appendOutput("  [\(channel.index)] \(name)", type: .response)
    }
  }

  private func formatRoute(_ contact: ContactDTO) -> String {
    if contact.isFloodRouted {
      L10n.Contacts.Contacts.Route.flood
    } else if contact.pathHopCount == 0 {
      L10n.Contacts.Contacts.Route.direct
    } else {
      L10n.Contacts.Contacts.Route.hops(contact.pathHopCount)
    }
  }
}
