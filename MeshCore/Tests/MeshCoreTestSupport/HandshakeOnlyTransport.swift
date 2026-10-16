import Foundation
import MeshCore

/// Completes appStart and leaves subsequent commands unanswered for lifecycle and timeout tests.
public actor HandshakeOnlyTransport: MeshTransport {
  public let receivedData: AsyncStream<Data>
  private let continuation: AsyncStream<Data>.Continuation
  public private(set) var isConnected = false
  public private(set) var sentData: [Data] = []

  public init() {
    (receivedData, continuation) = AsyncStream.makeStream(of: Data.self)
  }

  public func connect() {
    isConnected = true
  }

  public func disconnect() {
    isConnected = false
    continuation.finish()
  }

  public func send(_ data: Data) throws {
    guard isConnected else { throw MeshTransportError.notConnected }
    sentData.append(data)
    if data.first == CommandCode.appStart.rawValue {
      continuation.yield(Self.selfInfoPacket())
    }
  }

  private static func selfInfoPacket() -> Data {
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
