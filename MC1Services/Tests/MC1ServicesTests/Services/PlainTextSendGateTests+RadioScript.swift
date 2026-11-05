import Foundation
import MeshCore
import MeshCoreTestSupport
import os

extension PlainTextSendGateTests {
  final class RadioScript: @unchecked Sendable {
    private let hold = OSAllocatedUnfairLock(initialState: false)
    private let table = OSAllocatedUnfairLock(initialState: [Data]())

    func holdSendMessages(_ held: Bool) {
      hold.withLock { $0 = held }
    }

    func codes() -> [Data] {
      table.withLock { $0 }
    }

    func answer(_ transport: MockTransport) async {
      var responded = 1
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
            let code = table.withLock { slots -> Data in
              let sequence = UInt8(truncatingIfNeeded: slots.count)
              let ack = Data([attempt, sequence, 0x22, 0x33])
              if slots.count == 8 { slots.removeFirst() }
              slots.append(ack)
              return ack
            }
            var frame = Data([ResponseCode.messageSent.rawValue, 0])
            frame.append(code)
            frame.append(contentsOf: withUnsafeBytes(of: UInt32(10).littleEndian) { Array($0) })
            await transport.simulateReceive(frame)
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
  }
}
