import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import os

extension PlainTextSendGateTests {
  final class RadioScript: @unchecked Sendable {
    enum SendReply {
      case accepted
      case tableFull
      case missing
    }

    private let hold = OSAllocatedUnfairLock(initialState: false)
    private let table = OSAllocatedUnfairLock(initialState: [Data]())
    private let senderKey: Data?
    private let sendReply: @Sendable (UInt8, Int) -> SendReply
    private let suggestedTimeoutMs: @Sendable (UInt8) -> UInt32

    init(
      senderKey: Data? = nil,
      suggestedTimeoutMs: @escaping @Sendable (UInt8) -> UInt32 = { _ in 10 },
      sendReply: @escaping @Sendable (UInt8, Int) -> SendReply = { _, _ in .accepted }
    ) {
      self.senderKey = senderKey
      self.sendReply = sendReply
      self.suggestedTimeoutMs = suggestedTimeoutMs
    }

    func holdSendMessages(_ held: Bool) {
      hold.withLock { $0 = held }
    }

    func codes() -> [Data] {
      table.withLock { $0 }
    }

    func answer(_ transport: MockTransport) async {
      var responded = 1
      var submissions: [UInt8: Int] = [:]
      while !Task.isCancelled {
        let packets = await transport.sentData
        guard packets.count > responded else {
          try? await Task.sleep(for: .milliseconds(2))
          continue
        }
        var cursor = responded
        var paused = false
        for packet in packets[responded...] {
          if packet.first == CommandCode.sendMessage.rawValue, hold.withLock({ $0 }) {
            paused = true
            break
          }
          switch packet.first {
          case CommandCode.sendMessage.rawValue:
            let attempt = packet.count > 2 ? packet[2] : 0
            submissions[attempt, default: 0] += 1
            switch sendReply(attempt, submissions[attempt, default: 0]) {
            case .tableFull:
              await transport.simulateReceive(Data([
                ResponseCode.error.rawValue, FirmwareDeviceErrorCode.directMessageTableFull
              ]))
            case .missing:
              break
            case .accepted:
              let derivedAck = senderKey.map { expectedAck(packet, senderKey: $0) }
              let code = table.withLock { slots -> Data in
                let sequence = UInt8(truncatingIfNeeded: slots.count)
                let ack = derivedAck ?? Data([attempt, sequence, 0x22, 0x33])
                if slots.count == 8 { slots.removeFirst() }
                slots.append(ack)
                return ack
              }
              var frame = Data([ResponseCode.messageSent.rawValue, 0])
              frame.append(code)
              frame.append(contentsOf: withUnsafeBytes(of: suggestedTimeoutMs(attempt).littleEndian) { Array($0) })
              await transport.simulateReceive(frame)
            }
          case CommandCode.getContactByKey.rawValue:
            await transport.simulateReceive(Data([ResponseCode.error.rawValue]))
          default:
            await transport.simulateOK()
          }
          cursor += 1
        }
        responded = paused ? cursor : packets.count
        if paused { try? await Task.sleep(for: .milliseconds(2)) }
      }
    }

    private func expectedAck(_ packet: Data, senderKey: Data) -> Data {
      let timestamp = UInt32(packet[3]) | (UInt32(packet[4]) << 8)
        | (UInt32(packet[5]) << 16) | (UInt32(packet[6]) << 24)
      return AckCodeBuilder.expectedAck(
        timestamp: timestamp,
        attempt: packet[2],
        text: String(decoding: packet.dropFirst(13), as: UTF8.self),
        senderPublicKey: senderKey
      )
    }
  }
}
