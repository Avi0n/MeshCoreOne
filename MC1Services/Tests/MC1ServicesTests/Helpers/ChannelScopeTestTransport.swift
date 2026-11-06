import Foundation
@testable import MC1Services
import MeshCore

actor ChannelScopeTestTransport: MeshTransport {
  enum ChannelCommandOutcome: Sendable {
    case failWrite
    case accept
  }

  let receivedData: AsyncStream<Data>
  private let continuation: AsyncStream<Data>.Continuation
  private let scopeError: UInt8?
  private let channelOutcome: ChannelCommandOutcome
  private let beforePoolRetry: (@Sendable () async throws -> Void)?
  private(set) var isConnected = false
  private(set) var scopePackets: [Data] = []
  private(set) var scopesAtChannelSend: [Data] = []
  private(set) var sentFrames: [Data] = []

  init(
    scopeError: UInt8? = nil,
    channelOutcome: ChannelCommandOutcome = .failWrite,
    beforePoolRetry: (@Sendable () async throws -> Void)? = nil
  ) {
    self.scopeError = scopeError
    self.channelOutcome = channelOutcome
    self.beforePoolRetry = beforePoolRetry
    (receivedData, continuation) = AsyncStream.makeStream(of: Data.self)
  }

  func connect() {
    isConnected = true
  }

  func disconnect() {
    isConnected = false
    continuation.finish()
  }

  func send(_ data: Data) async throws {
    sentFrames.append(data)
    switch data.first {
    case CommandCode.appStart.rawValue:
      continuation.yield(selfInfoPacket())
    case CommandCode.setFloodScope.rawValue:
      scopePackets.append(data)
      if let scopeError {
        continuation.yield(Data([ResponseCode.error.rawValue, scopeError]))
      } else {
        continuation.yield(Data([ResponseCode.ok.rawValue]))
      }
    case CommandCode.sendChannelMessage.rawValue:
      scopesAtChannelSend.append(scopePackets.last ?? Data())
      if scopesAtChannelSend.count == 1, let beforePoolRetry {
        try await beforePoolRetry()
        continuation.yield(Data([ResponseCode.error.rawValue, FirmwareDeviceErrorCode.channelMessageNotFound]))
        return
      }
      switch channelOutcome {
      case .failWrite:
        // Stop before post-send bookkeeping reaches the OS notification center.
        throw MeshTransportError.sendFailed("Test transport send failure")
      case .accept:
        continuation.yield(Data([ResponseCode.ok.rawValue]))
      }
    case CommandCode.sendMessage.rawValue:
      continuation.yield(Data([ResponseCode.messageSent.rawValue, 0, 1, 2, 3, 4, 0x88, 0x13, 0, 0]))
    default:
      throw MeshTransportError.sendFailed("Unexpected test command")
    }
  }

  private func selfInfoPacket() -> Data {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 0x01, count: 32))
    packet.append(Data(repeating: 0, count: 12))
    for value: UInt32 in [915_000, 125_000] {
      packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    return packet
  }
}
