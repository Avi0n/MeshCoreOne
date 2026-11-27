import Foundation

struct WatchlistFailureStore {
  struct Record: Codable, Equatable, Sendable {
    enum Kind: String, Codable, Equatable, Sendable {
      case noReply
      case other
    }

    let kind: Kind
    let failedAt: Date
  }

  static let storageKey = "watchlistRefreshFailures"

  private let defaults: UserDefaults

  init(defaults: UserDefaults = .standard) {
    self.defaults = defaults
  }

  func load(radioID: UUID) -> [UUID: Record] {
    guard let entries = loadAll()[radioID.uuidString] else { return [:] }
    var result: [UUID: Record] = [:]
    for (key, record) in entries {
      if let id = UUID(uuidString: key) {
        result[id] = record
      }
    }
    return result
  }

  func save(_ records: [UUID: Record], for radioID: UUID) {
    var root = loadAll()
    if records.isEmpty {
      root.removeValue(forKey: radioID.uuidString)
    } else {
      root[radioID.uuidString] = Dictionary(uniqueKeysWithValues: records.map { ($0.key.uuidString, $0.value) })
    }
    if root.isEmpty {
      defaults.removeObject(forKey: Self.storageKey)
    } else if let data = try? JSONEncoder().encode(root) {
      defaults.set(data, forKey: Self.storageKey)
    }
  }

  private func loadAll() -> [String: [String: Record]] {
    guard let data = defaults.data(forKey: Self.storageKey),
          let decoded = try? JSONDecoder().decode([String: [String: Record]].self, from: data)
    else { return [:] }
    return decoded
  }
}
