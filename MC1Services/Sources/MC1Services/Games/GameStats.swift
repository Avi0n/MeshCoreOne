import Foundation

/// How a finished game ended for us.
public enum GameResult: String, Sendable, Codable {
  case win, loss, draw
}

/// Wins, draws and losses against one opponent in one game.
public struct GameTally: Sendable, Equatable, Codable {
  public var wins = 0
  public var draws = 0
  public var losses = 0

  public init(wins: Int = 0, draws: Int = 0, losses: Int = 0) {
    self.wins = wins
    self.draws = draws
    self.losses = losses
  }

  public var played: Int { wins + draws + losses }

  mutating func add(_ result: GameResult) {
    switch result {
    case .win: wins += 1
    case .draw: draws += 1
    case .loss: losses += 1
    }
  }
}

/// Head-to-head record per opponent and game, kept on this device.
/// Games are keyed by `GameStatsStore.chess` or a `MeshGameKind` raw value.
public actor GameStatsStore {
  public static let chess = "chess"

  private let storeURL: URL?
  /// Opponent (first 6 key bytes, hex) → game → tally.
  private var tallies: [String: [String: GameTally]] = [:]

  public init(storeURL: URL? = GameStatsStore.defaultStoreURL) {
    self.storeURL = storeURL
    if let storeURL, let data = try? Data(contentsOf: storeURL),
       let saved = try? JSONDecoder().decode([String: [String: GameTally]].self, from: data) {
      tallies = saved
    }
  }

  public static var defaultStoreURL: URL {
    URL.applicationSupportDirectory.appending(path: "Games/stats.json")
  }

  public func record(_ result: GameResult, game: String, opponentKey: Data) {
    tallies[Self.opponentID(opponentKey), default: [:]][game, default: GameTally()].add(result)
    save()
  }

  /// Our record against this opponent, per game.
  public func tallies(opponentKey: Data) -> [String: GameTally] {
    tallies[Self.opponentID(opponentKey)] ?? [:]
  }

  /// Keys arrive as full public keys or 6-byte prefixes; the prefix identifies the node.
  private static func opponentID(_ key: Data) -> String {
    key.prefix(6).map { String(format: "%02x", $0) }.joined()
  }

  private func save() {
    guard let storeURL else { return }
    try? FileManager.default.createDirectory(at: storeURL.deletingLastPathComponent(), withIntermediateDirectories: true)
    if let data = try? JSONEncoder().encode(tallies) {
      try? data.write(to: storeURL, options: .atomic)
    }
  }
}
