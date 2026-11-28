import CryptoKit
import Foundation

/// Result of a finished game. Players are 0 (inviter, moves first) and 1.
public enum GameOutcome: Sendable, Equatable, Codable {
  case win(Int)
  case draw
}

/// Rules of a two-player, turn-based game with public information.
public protocol TurnRules: Sendable, Equatable, Codable {
  /// Whose move it is (0 or 1).
  var turn: Int { get }
  var outcome: GameOutcome? { get }
  /// Applies `move` (wire text) by `player`. Returns false and leaves the state unchanged if illegal.
  mutating func apply(_ move: String, by player: Int) -> Bool
}

public struct GridCell: Sendable, Hashable, Codable {
  public let row: Int
  public let col: Int

  public init(row: Int, col: Int) {
    self.row = row
    self.col = col
  }
}

/// Commit–reveal for games with a hidden secret (the battleship fleet): each side
/// sends `commitment(secret, salt)` before play and reveals both at the end.
public enum GameSecret {
  public static func randomSalt() -> String {
    let alphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789")
    return String((0..<8).map { _ in alphabet.randomElement()! })
  }

  public static func commitment(_ secret: String, salt: String) -> String {
    let digest = SHA256.hash(data: Data((secret + "|" + salt).utf8))
    return digest.prefix(16).map { String(format: "%02x", $0) }.joined()
  }
}
