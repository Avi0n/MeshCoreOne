import Foundation

/// How a game ended, seen from our side. The app turns it into localized text.
public enum GameEnding: Sendable, Equatable, Codable {
  case youResigned
  case opponentResigned
  case opponentDeclined
  /// Chess: the opponent's CoreChess reset the board mid-game.
  case opponentResetBoard
  case inviteUnanswered
  /// Chess: the accept or the board setup never got through.
  case setupFailed
  case youWon
  case opponentWon
  case draw
  case drawAgreed
  case checkmate(youWon: Bool)
  case stalemate
  case fiftyMoveRule
  case insufficientMaterial
  case threefoldRepetition

  /// Ended by the player's own action, so there is nothing to notify them about.
  public var isOwnDoing: Bool {
    self == .youResigned || self == .youWon
  }
}

/// A warning shown above the board while a game runs. The app turns it into localized text.
public enum GameNotice: Sendable, Equatable, Codable {
  case moveNotAcknowledged
  /// We accepted, but the inviter has not confirmed the game yet.
  case opponentNotConfirmed
  /// A move arrived that does not fit the board; carries the raw move.
  case invalidMove(String)
  /// Chess: the opponent's move does not fit our board, a resync is under way.
  case resyncing
  /// Chess: the board was replaced with the opponent's.
  case resynced
}
