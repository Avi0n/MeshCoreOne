import MC1Services
import Observation
import SwiftUI

/// App-side state for the chess game: mirrors `ChessGameService` and drives presentation.
@Observable
@MainActor
final class ChessCoordinator {
  static let shared = ChessCoordinator()

  private(set) var game: ChessGame?
  var isBoardPresented = false
  var isInvitePresented = false

  @ObservationIgnored private(set) var service: ChessGameService?
  @ObservationIgnored private var updatesTask: Task<Void, Never>?
  @ObservationIgnored private weak var notifications: NotificationService?
  @ObservationIgnored private var hasSnapshot = false

  /// 1 when the chess game waits on the player.
  var attentionCount: Int {
    game.map { $0.phase != .over && $0.status.needsMe } == true ? 1 : 0
  }

  func attach(_ service: ChessGameService?, notifications: NotificationService?) {
    self.notifications = notifications
    guard service !== self.service else { return }
    hasSnapshot = false
    self.service = service
    updatesTask?.cancel()
    guard let service else { return }
    updatesTask = Task { [weak self] in
      for await game in await service.updates() {
        self?.apply(game)
      }
    }
  }

  private func apply(_ newGame: ChessGame?) {
    let before = game
    let previous = game?.phase
    game = newGame
    if hasSnapshot, let newGame { notifyIfNeeded(newGame, before: before) }
    hasSnapshot = true
    guard let newGame else {
      isInvitePresented = false
      return
    }
    if newGame.phase == .invited, previous != .invited {
      isInvitePresented = true
    }
    // Open the board when a game starts.
    if newGame.phase == .playing, previous != .playing, previous != nil {
      isBoardPresented = true
    }
  }

  private func notifyIfNeeded(_ game: ChessGame, before: ChessGame?) {
    guard let body = Self.notificationBody(game, before: before) else { return }
    if isBoardPresented, UIApplication.shared.applicationState == .active { return }
    Task { await notifications?.postGameNotification(title: GameL10n.gameWithOpponent(GameL10n.Kind.chess, game.opponentName), body: body, gameID: "chess") }
  }

  static func notificationBody(_ game: ChessGame, before: ChessGame?) -> String? {
    guard let before, GameNodes.same(before.opponentKey, game.opponentKey) else {
      return game.phase == .invited ? GameL10n.Chess.invitesYou(game.opponentName) : nil
    }
    let opponentMoved = game.history.count > before.history.count && !before.isMyTurn
    if game.phase == .over, before.phase != .over {
      let ourMove = game.history.count > before.history.count && before.isMyTurn
      guard !ourMove, let ending = game.ending, !ending.isOwnDoing else { return nil }
      return ending.text(opponent: game.opponentName)
    }
    if game.phase == .playing, before.phase != .playing, game.isMyTurn {
      return GameL10n.Notification.started
    }
    if game.phase == .playing, opponentMoved, game.isMyTurn {
      return GameL10n.Notification.played(game.opponentName, game.history.last ?? "")
    }
    if game.drawOfferedByOpponent, !before.drawOfferedByOpponent {
      return GameL10n.Chess.drawOffered(game.opponentName)
    }
    if game.ackFailed, !before.ackFailed {
      return GameL10n.Notification.notConfirmed
    }
    return nil
  }

  func clearNotification() {
    notifications?.removeGameNotification(gameID: "chess")
  }

  func invite(_ contact: ContactDTO) {
    if let game, game.phase == .playing || game.phase == .inviting || game.phase == .waitingForSetup {
      isBoardPresented = true
      return
    }
    isBoardPresented = true
    Task { await service?.invite(contactKey: contact.publicKey, name: contact.displayName) }
  }

  func accept() {
    isInvitePresented = false
    isBoardPresented = true
    Task { await service?.acceptInvite() }
  }

  func decline() {
    isInvitePresented = false
    Task { await service?.declineInvite() }
  }

  func play(from: ChessPosition.Square, to: ChessPosition.Square) {
    Task { await service?.play(from: from, to: to) }
  }

  func resend() {
    Task { await service?.resendLastMove() }
  }

  func resign() {
    Task { await service?.resign() }
  }

  func offerDraw() {
    Task { await service?.offerDraw() }
  }

  func answerDraw(_ accept: Bool) {
    Task { await service?.answerDraw(accept: accept) }
  }

  func close() {
    isBoardPresented = false
    Task { await service?.close() }
  }
}
