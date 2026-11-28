import MC1Services

/// Localized strings for games (`Games.strings`).
typealias GameL10n = L10n.Games.Games

extension MeshGameKind {
  var title: String {
    switch self {
    case .battleship: GameL10n.Kind.battleship
    }
  }
}

extension GameEnding {
  func text(opponent: String) -> String {
    switch self {
    case .youResigned: GameL10n.Ending.youResigned
    case .opponentResigned: GameL10n.Ending.opponentResigned(opponent)
    case .opponentDeclined: GameL10n.Ending.opponentDeclined(opponent)
    case .opponentResetBoard: GameL10n.Ending.opponentResetBoard(opponent)
    case .inviteUnanswered: GameL10n.Ending.inviteUnanswered
    case .setupFailed: GameL10n.Ending.setupFailed
    case .youWon: GameL10n.Ending.youWon
    case .opponentWon: GameL10n.Ending.opponentWon(opponent)
    case .draw: GameL10n.Ending.draw
    case .drawAgreed: GameL10n.Ending.drawAgreed
    case let .checkmate(youWon):
      youWon ? GameL10n.Ending.checkmateYouWon : GameL10n.Ending.checkmateOpponentWon(opponent)
    case .stalemate: GameL10n.Ending.stalemate
    case .fiftyMoveRule: GameL10n.Ending.fiftyMoveRule
    case .insufficientMaterial: GameL10n.Ending.insufficientMaterial
    case .threefoldRepetition: GameL10n.Ending.threefoldRepetition
    }
  }
}

extension GameNotice {
  func text(opponent: String) -> String {
    switch self {
    case .moveNotAcknowledged: GameL10n.Notice.moveNotAcknowledged
    case .opponentNotConfirmed: GameL10n.Notice.opponentNotConfirmed(opponent)
    case let .invalidMove(move): GameL10n.Notice.invalidMove(move)
    case .resyncing: GameL10n.Notice.resyncing
    case .resynced: GameL10n.Notice.resynced(opponent)
    }
  }
}

extension Battleship.CheatReason {
  var text: String {
    switch self {
    case .noCommitment: GameL10n.Battleship.Cheat.noCommitment
    case .fleetMismatch: GameL10n.Battleship.Cheat.fleetMismatch
    case .invalidLayout: GameL10n.Battleship.Cheat.invalidLayout
    case let .falseAnswer(cell, reportedHit: true): GameL10n.Battleship.Cheat.falseHit(cell)
    case let .falseAnswer(cell, reportedHit: false): GameL10n.Battleship.Cheat.falseMiss(cell)
    }
  }
}

extension MeshGame {
  /// The ending as text, or a plain "game over" when it is not known.
  var endingText: String { ending?.text(opponent: opponentName) ?? GameL10n.Sheet.over }
}

extension ChessGame {
  var endingText: String { ending?.text(opponent: opponentName) ?? GameL10n.Sheet.over }
}
