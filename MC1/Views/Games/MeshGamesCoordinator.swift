import MC1Services
import Observation
import SwiftUI

/// App-side state for battleship.
@Observable
@MainActor
final class MeshGamesCoordinator {
  static let shared = MeshGamesCoordinator()

  private(set) var games: [MeshGame] = []
  var presentedGameID: String?
  var inviteGameID: String?
  var isInvitePresented = false

  @ObservationIgnored private var service: MeshGameService?
  @ObservationIgnored private var updatesTask: Task<Void, Never>?
  @ObservationIgnored private var announcedInvites: Set<String> = []
  @ObservationIgnored private weak var notifications: NotificationService?
  @ObservationIgnored private var hasSnapshot = false

  /// Games waiting on the player: their move, an invite or a resend.
  var attentionCount: Int { games.filter { $0.phase != .over && $0.status.needsMe }.count }

  func attach(_ service: MeshGameService?, notifications: NotificationService?) {
    self.notifications = notifications
    guard service !== self.service else { return }
    hasSnapshot = false
    self.service = service
    updatesTask?.cancel()
    guard let service else { return }
    updatesTask = Task { [weak self] in
      for await games in await service.updates() {
        self?.apply(games)
      }
    }
  }

  private func apply(_ newGames: [MeshGame]) {
    let before = Dictionary(uniqueKeysWithValues: games.map { ($0.id, $0) })
    let previous = before.mapValues(\.phase)
    games = newGames
    if hasSnapshot {
      for game in newGames { notifyIfNeeded(game, before: before[game.id]) }
    }
    hasSnapshot = true
    if let invite = newGames.first(where: { $0.phase == .invited && !announcedInvites.contains($0.id) }) {
      announcedInvites.insert(invite.id)
      inviteGameID = invite.id
      isInvitePresented = true
    }
    // Our invitation was accepted: open the board.
    if let started = newGames.first(where: { $0.phase == .playing && previous[$0.id] == .inviting }) {
      presentedGameID = started.id
    }
  }

  private func notifyIfNeeded(_ game: MeshGame, before: MeshGame?) {
    guard let body = Self.notificationBody(game, before: before) else { return }
    if presentedGameID == game.id, UIApplication.shared.applicationState == .active { return }
    let title = GameL10n.gameWithOpponent(game.kind.title, game.opponentName)
    Task { await notifications?.postGameNotification(title: title, body: body, gameID: game.id) }
  }

  static func notificationBody(_ game: MeshGame, before: MeshGame?) -> String? {
    guard let before else {
      return game.phase == .invited ? GameL10n.Notification.invite(game.opponentName) : nil
    }
    let opponentMoved = game.moves.count > before.moves.count && !before.isMyTurn
    if game.phase == .over, before.phase != .over {
      let ourMove = game.moves.count > before.moves.count && before.isMyTurn
      guard !ourMove, let ending = game.ending, !ending.isOwnDoing else { return nil }
      return ending.text(opponent: game.opponentName)
    }
    if before.phase == .inviting, game.phase == .playing {
      return GameL10n.Notification.accepted(game.opponentName)
    }
    if game.phase == .playing, opponentMoved, game.isMyTurn {
      // Battleship moves are wire codes, not something to read in a notification.
      return GameL10n.Notification.moved(game.opponentName)
    }
    if game.ackFailed, !before.ackFailed {
      return GameL10n.Notification.notConfirmed
    }
    return nil
  }

  func clearNotification(_ id: String) {
    notifications?.removeGameNotification(gameID: id)
  }

  func game(_ id: String?) -> MeshGame? {
    games.first { $0.id == id }
  }

  func invite(_ kind: MeshGameKind, contact: ContactDTO) {
    Task {
      guard let id = await service?.invite(kind, contactKey: contact.publicKey, name: contact.displayName) else { return }
      presentedGameID = id
    }
  }

  /// Accepts an invite; for battleship with the `fleet` placed on the board (nil keeps it).
  func accept(_ id: String, fleet: [Battleship.Ship]? = nil) {
    isInvitePresented = false
    presentedGameID = id
    Task { await service?.accept(id, fleet: fleet) }
  }

  /// "Play" on the invite alert: battleship opens the board to place the fleet first.
  func answerInvite(_ id: String) {
    guard game(id)?.kind == .battleship else { return accept(id) }
    isInvitePresented = false
    presentedGameID = id
  }

  func sendInvite(_ id: String, fleet: [Battleship.Ship]?) {
    Task { await service?.sendInvite(id, fleet: fleet) }
  }

  func decline(_ id: String) {
    isInvitePresented = false
    Task { await service?.decline(id) }
  }

  func play(_ id: String, move: String) {
    Task { await service?.play(id, move: move) }
  }

  func resend(_ id: String) { Task { await service?.resendLastMove(id) } }
  func resign(_ id: String) { Task { await service?.resign(id) } }

  func remove(_ id: String) {
    if presentedGameID == id { presentedGameID = nil }
    Task { await service?.remove(id) }
  }
}
