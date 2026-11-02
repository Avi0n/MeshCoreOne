import Foundation

extension MeshCoreSession {
  private static let cliNullByte: UInt8 = 0

  /// Typed commands accept only the exact request tag reflected by supported firmware.
  public func runCorrelatedCliCommand(_ command: String) async throws -> String {
    try validateCLICommand(command)
    return try await requestResponseSerializer.withSerialization { [self] in
      let prefix = try radioCLICorrelationScope.allocate()
      let taggedCommand = prefix + command
      try await validateCLICommand(taggedCommand)
      let reply = try await performCLIExchange(taggedCommand, expectedPrefix: prefix)
      return String(decoding: reply.utf8.dropFirst(prefix.utf8.count), as: UTF8.self)
    }
  }

  func runVerbatimCLICommand(_ command: String) async throws -> String {
    try validateCLICommand(command, allowsNullBytes: true)
    return try await requestResponseSerializer.withSerialization { [self] in
      let prefix = RadioCLICorrelationScope.commandPrefix(command)
      if let prefix {
        try radioCLICorrelationScope.reserve(prefix)
      } else {
        try radioCLICorrelationScope.requireUnprefixedReplyAuthority()
      }
      do {
        return try await performCLIExchange(command, expectedPrefix: prefix)
      } catch {
        if prefix == nil { radioCLICorrelationScope.noteUnprefixedReplyUncertainty() }
        throw error
      }
    }
  }

  private func performCLIExchange(_ command: String, expectedPrefix: String?) async throws -> String {
    let scope = radioCLICorrelationScope
    return try await sendAndMatchHoldingSerialization(PacketBuilder.runCliCommand(command)) { event in
      if case let .cliReply(text) = event {
        if let expectedPrefix {
          return text.utf8.starts(with: expectedPrefix.utf8) ? .success(text) : .ignore
        }
        return scope.recognizesReplyPrefix(text) ? .ignore : .success(text)
      }
      if expectedPrefix == nil, let error = Self.deviceErrorMatcher(event) { return .failure(error) }
      return .ignore
    }
  }

  private func validateCLICommand(_ command: String, allowsNullBytes: Bool = false) throws {
    guard allowsNullBytes || !command.utf8.contains(Self.cliNullByte) else {
      throw MeshCoreError.invalidInput("CLI text contains a null character")
    }
    let isBlank = command.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    guard !isBlank, command.utf8.count >= PacketBuilder.runCliCommandMinUTF8Bytes else {
      throw MeshCoreError.invalidInput(isBlank ? "CLI command is empty" : "CLI command is too short")
    }
    guard command.utf8.count <= PacketBuilder.runCliCommandMaxUTF8Bytes else {
      throw MeshCoreError.invalidInput("CLI command exceeds maximum length")
    }
  }
}
