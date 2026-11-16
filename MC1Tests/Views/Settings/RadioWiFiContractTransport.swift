import Foundation
import MC1Services

actor RadioWiFiContractTransport: MeshTransport {
  private var firmware = RadioWiFiFirmware(defaultSSID: "initial")
  private let stream: AsyncStream<Data>
  private let continuation: AsyncStream<Data>.Continuation
  private var holds: [String: Int] = [:]
  private var held: [String: [Data]] = [:]
  private var rejected: Set<String> = []
  private var unsupported: Set<String> = []
  private var droppedBeforeApply: Set<String> = []
  private var reflectsPrefixes = true
  private(set) var commands: [String] = []
  private(set) var sentData: [Data] = []
  private(set) var isConnected = false

  init() {
    (stream, continuation) = AsyncStream.makeStream(of: Data.self)
  }

  var receivedData: AsyncStream<Data> {
    stream
  }

  func connect() {
    isConnected = true
  }

  func disconnect() {
    isConnected = false
    continuation.finish()
  }

  func send(_ data: Data) throws {
    guard isConnected else { throw MeshTransportError.notConnected }
    guard let code = data.first else { return }
    sentData.append(data)
    if code == CommandCode.getStats.rawValue {
      var reply = Data([ResponseCode.stats.rawValue, 0, 0x74, 0x0E])
      reply.append(withUnsafeBytes(of: UInt32(1000).littleEndian) { Data($0) })
      reply.append(contentsOf: [0, 0, 0])
      continuation.yield(reply)
      return
    }
    if code == CommandCode.appStart.rawValue {
      continuation.yield(Self.selfInfoPacket())
      return
    }
    guard code == CommandCode.runCliCommand.rawValue else { return }
    let command = String(decoding: data.dropFirst(), as: UTF8.self)
    commands.append(command)
    let firmwareCommand = String(decoding: command.utf8.prefix(while: { $0 != 0 }), as: UTF8.self)
    let effective = Self.effective(firmwareCommand)
    if droppedBeforeApply.remove(effective) != nil { return }
    let reply: Data
    if rejected.remove(effective) != nil {
      let text = RadioWiFiFirmware.reflectedReply("Error: rejected", to: firmwareCommand)
      reply = Data([ResponseCode.cliReply.rawValue]) + Data(text.utf8)
    } else {
      var text = unsupported.contains(effective)
        ? RadioWiFiFirmware.reflectedReply("Unknown command", to: firmwareCommand)
        : firmware.reply(to: firmwareCommand) ?? "Unknown command"
      if !reflectsPrefixes {
        let prefix = RadioWiFiFirmware.reflectedReply("", to: firmwareCommand)
        text = String(text.dropFirst(prefix.count))
      }
      reply = Data([ResponseCode.cliReply.rawValue]) + Data(text.utf8)
    }
    if holds[effective, default: 0] > 0 {
      holds[effective, default: 0] -= 1
      held[effective, default: []].append(reply)
    } else {
      continuation.yield(reply)
    }
  }

  func useFirmware(_ firmware: RadioWiFiFirmware) {
    self.firmware = firmware
  }

  func setReflectsPrefixes(_ value: Bool) {
    reflectsPrefixes = value
  }

  func rejectNext(_ command: String) {
    rejected.insert(command)
  }

  func dropNextBeforeApply(_ command: String) {
    droppedBeforeApply.insert(command)
  }

  func markUnsupported(_ command: String) {
    unsupported.insert(command)
  }

  func support(_ command: String) {
    unsupported.remove(command)
  }

  func holdNext(_ command: String) {
    holds[command, default: 0] += 1
  }

  func heldCount(_ command: String) -> Int {
    held[command]?.count ?? 0
  }

  func count(_ command: String) -> Int {
    commands.filter { Self.effective($0) == command }.count
  }

  func release(_ command: String) {
    for reply in held.removeValue(forKey: command) ?? [] {
      continuation.yield(reply)
    }
  }

  func releaseAll() {
    holds.removeAll()
    for replies in held.values {
      for reply in replies {
        continuation.yield(reply)
      }
    }
    held.removeAll()
  }

  func injectCLIReply(_ text: String) {
    continuation.yield(Data([ResponseCode.cliReply.rawValue]) + Data(text.utf8))
  }

  func effectiveCommands() -> [String] {
    commands.map(Self.effective)
  }

  static func effective(_ command: String) -> String {
    var bytes = Array(command.utf8.drop(while: { $0 == 0x20 }))
    if bytes.count > 4, bytes[2] == 0x7C { bytes.removeFirst(3) }
    return String(decoding: bytes, as: UTF8.self)
  }

  private static func selfInfoPacket() -> Data {
    var reply = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    reply.append(Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize))
    reply.append(Data(repeating: 0, count: 12))
    for value: UInt32 in [915_000, 125_000] {
      reply.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    reply.append(contentsOf: [7, 5])
    reply.append(contentsOf: "Contract Radio".utf8)
    return reply
  }
}
