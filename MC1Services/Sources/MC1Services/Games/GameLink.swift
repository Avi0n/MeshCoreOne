import Foundation
import MeshCore

/// One repeater on a route, by its hash prefix and, when we know it, its name.
public struct GameHop: Sendable, Codable, Equatable {
  public var hex: String
  public var name: String?

  public init(hex: String, name: String? = nil) {
    self.hex = hex
    self.name = name
  }

  public var label: String {
    name.map { "\($0) (\(hex))" } ?? hex
  }
}

/// What the radio told us about one game DM we sent.
public struct GameSendReceipt: Sendable {
  public var flood: Bool
  /// The route the packet takes when it is sent direct.
  public var hops: [GameHop]
  /// Resolves with the round trip in ms once the opponent's radio acknowledges the DM,
  /// or nil when no acknowledgement arrives in time.
  public var radioAck: Task<UInt32?, Never>?

  public init(flood: Bool, hops: [GameHop] = [], radioAck: Task<UInt32?, Never>? = nil) {
    self.flood = flood
    self.hops = hops
    self.radioAck = radioAck
  }
}

/// How a received game DM got to us.
public struct GameRxInfo: Sendable, Equatable {
  public var flood: Bool
  public var hopCount: Int?
  /// For a direct DM the hops are consumed in transit: this is the contact's route reversed.
  public var estimatedHops: [GameHop]
  public var snr: Double?

  public init(flood: Bool, hopCount: Int? = nil, estimatedHops: [GameHop] = [], snr: Double? = nil) {
    self.flood = flood
    self.hopCount = hopCount
    self.estimatedHops = estimatedHops
    self.snr = snr
  }
}

/// One game DM sent or received, kept per game for the debug view.
public struct GameLogEntry: Sendable, Codable, Equatable, Identifiable {
  public enum Direction: String, Sendable, Codable { case sent, received }
  public enum RadioAck: String, Sendable, Codable { case waiting, delivered, missing }

  public var id = UUID()
  public var date = Date()
  public var direction: Direction
  /// Protocol message type, e.g. `MOV`, `ACK`, `INV`.
  public var type: String
  public var text: String
  /// 1 for the first send, then 2, 3… for retries.
  public var attempt = 1
  public var flood: Bool?
  public var hops: [GameHop] = []
  public var hopCount: Int?
  public var snr: Double?
  public var radioAck: RadioAck?
  public var tripTimeMs: Int?
  public var error: String?

  public init(direction: Direction, type: String, text: String, attempt: Int = 1) {
    self.direction = direction
    self.type = type
    self.text = text
    self.attempt = attempt
  }

  static func sent(_ type: String, _ text: String, attempt: Int, receipt: GameSendReceipt?, error: Error?) -> GameLogEntry {
    var entry = GameLogEntry(direction: .sent, type: type, text: text, attempt: attempt)
    if let receipt {
      entry.flood = receipt.flood
      entry.hops = receipt.hops
      entry.hopCount = receipt.flood ? nil : receipt.hops.count
      entry.radioAck = receipt.radioAck == nil ? nil : .waiting
    }
    entry.error = error?.localizedDescription
    return entry
  }

  static func received(_ type: String, _ text: String, rx: GameRxInfo?) -> GameLogEntry {
    var entry = GameLogEntry(direction: .received, type: type, text: text)
    if let rx {
      entry.flood = rx.flood
      entry.hopCount = rx.hopCount
      entry.hops = rx.estimatedHops
      entry.snr = rx.snr
    }
    return entry
  }
}

/// Where our last move is on its way to the opponent.
public enum GameDelivery: Sendable, Equatable {
  /// Handed to the radio; nothing heard back yet.
  case sending(attempt: Int, flood: Bool?)
  /// The opponent's radio acknowledged it; their app has not confirmed yet.
  case reachedRadio(attempt: Int)
  /// The opponent's app confirmed the move.
  case confirmed
  /// All retries ran out without a confirmation.
  case failed
}

extension [GameLogEntry] {
  /// Keeps the newest entries only.
  static let limit = 300

  mutating func appendCapped(_ entry: GameLogEntry) {
    append(entry)
    if count > Self.limit { removeFirst(count - Self.limit) }
  }

  mutating func setRadioAck(_ id: UUID, tripTime: UInt32?) {
    guard let index = firstIndex(where: { $0.id == id }) else { return }
    self[index].radioAck = tripTime == nil ? .missing : .delivered
    self[index].tripTimeMs = tripTime.map(Int.init)
  }

  /// The state of our latest move, or nil when the opponent has moved since.
  public func moveDelivery(awaitingAck: Bool, ackFailed: Bool) -> GameDelivery? {
    guard let lastSent = lastIndex(where: { $0.direction == .sent && $0.type == "MOV" }) else { return nil }
    if let lastReceived = lastIndex(where: { $0.direction == .received && $0.type == "MOV" }),
       lastReceived > lastSent {
      return nil
    }
    if ackFailed { return .failed }
    guard awaitingAck else { return .confirmed }
    let text = self[lastSent].text
    let sends = filter { $0.direction == .sent && $0.text == text }
    let attempt = sends.last?.attempt ?? 1
    if sends.contains(where: { $0.radioAck == .delivered }) { return .reachedRadio(attempt: attempt) }
    return .sending(attempt: attempt, flood: self[lastSent].flood)
  }
}

/// Sends game DMs through the radio and reports route and delivery.
struct GameRadioLink: Sendable {
  let session: MeshCoreSession
  let dataStore: PersistenceStore
  let radioID: UUID

  func send(to key: Data, text: String) async throws -> GameSendReceipt {
    // Subscribe before sending so a fast acknowledgement is not missed.
    let acks = await session.events(filter: .anyAcknowledgement)
    let info = try await session.sendMessage(to: Data(key.prefix(6)), text: text, timestamp: Date(), attempt: 0)
    let flood = info.route == 1
    var hops: [GameHop] = []
    if !flood, let contact = try? await dataStore.fetchContact(radioID: radioID, publicKeyPrefix: Data(key.prefix(6))) {
      hops = await named(Self.hops(pathLength: contact.outPathLength, path: contact.outPath))
    }
    let expected = info.expectedAck
    let timeout = Swift.max(Double(info.suggestedTimeoutMs) / 1000 * 1.5, 10)
    let radioAck = Task<UInt32?, Never> {
      await withTaskGroup(of: UInt32?.self) { group in
        group.addTask {
          for await event in acks {
            if case let .acknowledgement(code, tripTime) = event, code == expected { return tripTime ?? 0 }
          }
          return nil
        }
        group.addTask {
          try? await Task.sleep(for: .seconds(timeout))
          return nil
        }
        let result = await group.next() ?? nil
        group.cancelAll()
        return result
      }
    }
    return GameSendReceipt(flood: flood, hops: hops, radioAck: radioAck)
  }

  func rxInfo(_ message: ContactMessage, contact: ContactDTO?) async -> GameRxInfo {
    let direct = message.pathLength == PacketBuilder.floodPathSentinel
    guard direct else {
      return GameRxInfo(flood: true, hopCount: Int(message.pathLength & 0x3F), snr: message.snr)
    }
    var estimate: [GameHop] = []
    if let contact {
      estimate = await named(Self.hops(pathLength: contact.outPathLength, path: contact.outPath).reversed())
    }
    return GameRxInfo(flood: false, hopCount: estimate.isEmpty ? nil : estimate.count, estimatedHops: estimate, snr: message.snr)
  }

  /// Splits an encoded route into per-hop hashes.
  static func hops(pathLength: UInt8, path: Data) -> [Data] {
    guard pathLength != PacketBuilder.floodPathSentinel else { return [] }
    let hashSize = Int(pathLength >> 6) + 1
    let count = Int(pathLength & 0x3F)
    let bytes = [UInt8](path)
    guard count > 0, bytes.count >= count * hashSize else { return [] }
    return (0..<count).map { Data(bytes[($0 * hashSize)..<(($0 + 1) * hashSize)]) }
  }

  private func named(_ hashes: [Data]) async -> [GameHop] {
    guard !hashes.isEmpty else { return [] }
    let contacts = await (try? dataStore.fetchContacts(radioID: radioID)) ?? []
    return hashes.map { hash in
      let matches = contacts.filter { $0.publicKey.starts(with: hash) }
      let match = matches.first { $0.type == .repeater } ?? matches.first
      return GameHop(hex: hash.map { String(format: "%02X", $0) }.joined(), name: match?.displayName)
    }
  }
}
