import Foundation
import Synchronization

/// CLI request tags remain reserved until their connection request retires.
public final class RadioCLICorrelationScope: Sendable {
  private struct State {
    var nextOrdinal = 0
    var reserved: Set<Data> = []
    var unprefixedReplyIsUncertain = false
  }

  private static let alphabet = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".utf8)
  static let capacity = alphabet.count * alphabet.count
  private static let prefixMarker: UInt8 = 0x7C
  private static let spaceByte: UInt8 = 0x20
  private static let prefixMarkerOffset = 2
  private static let prefixSize = 3
  private static let minimumPrefixedCommandBytes = 5
  private let state = Mutex(State())

  public init() {}

  func allocate() throws -> String {
    try state.withLock { state in
      while state.nextOrdinal < Self.capacity {
        let ordinal = state.nextOrdinal
        state.nextOrdinal += 1
        let bytes = [
          Self.alphabet[ordinal / Self.alphabet.count],
          Self.alphabet[ordinal % Self.alphabet.count], Self.prefixMarker
        ]
        let prefix = String(decoding: bytes, as: UTF8.self)
        if state.reserved.insert(Data(prefix.utf8)).inserted { return prefix }
      }
      throw MeshCoreError.invalidInput("CLI request correlation is exhausted")
    }
  }

  func reserve(_ prefix: String) throws {
    try state.withLock { state in
      guard state.reserved.insert(Data(prefix.utf8)).inserted else {
        throw MeshCoreError.invalidInput("CLI request prefix was already used")
      }
    }
  }

  func requireUnprefixedReplyAuthority() throws {
    try state.withLock { state in
      guard !state.unprefixedReplyIsUncertain else {
        throw MeshCoreError.invalidInput("CLI reply provenance requires a new connection")
      }
    }
  }

  func noteUnprefixedReplyUncertainty() {
    state.withLock { $0.unprefixedReplyIsUncertain = true }
  }

  func recognizesReplyPrefix(_ reply: String) -> Bool {
    let bytes = Array(reply.utf8.prefix(Self.prefixSize))
    guard bytes.count == Self.prefixSize, bytes.last == Self.prefixMarker else { return false }
    return state.withLock { $0.reserved.contains(Data(bytes)) }
  }

  static func commandPrefix(_ command: String) -> String? {
    let bytes = command.utf8.drop(while: { $0 == Self.spaceByte })
    guard bytes.count >= Self.minimumPrefixedCommandBytes,
          bytes[bytes.index(bytes.startIndex, offsetBy: Self.prefixMarkerOffset)] == Self.prefixMarker else {
      return nil
    }
    return String(decoding: bytes.prefix(Self.prefixSize), as: UTF8.self)
  }
}
