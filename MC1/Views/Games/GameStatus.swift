import MC1Services
import SwiftUI

/// One line describing where a game stands, shared by the game sheets, the DM strip and the games list.
struct GameStatus: Equatable {
  enum Tone { case attention, progress, waiting, problem, done }

  var text: String
  var symbol: String
  var tone: Tone

  var color: Color {
    switch tone {
    case .attention: .accentColor
    case .progress: .blue
    case .waiting: .secondary
    case .problem: .orange
    case .done: .secondary
    }
  }

  /// The player has something to do: move, answer an invite or resend.
  var needsMe: Bool {
    tone == .attention || tone == .problem
  }

  static func delivery(_ delivery: GameDelivery, opponent: String) -> GameStatus {
    switch delivery {
    case let .sending(attempt, flood):
      var text = switch flood {
      case true?: GameL10n.Status.sendingFlood
      case false?: GameL10n.Status.sendingDirect
      case nil: GameL10n.Status.sending
      }
      if attempt > 1 { text += " · " + GameL10n.Status.attempt(attempt) }
      return GameStatus(text: text, symbol: "arrow.up.circle", tone: .progress)
    case .reachedRadio:
      return GameStatus(text: GameL10n.Status.reachedRadio(opponent), symbol: "checkmark.circle", tone: .progress)
    case .confirmed:
      return GameStatus(text: GameL10n.Status.confirmed(opponent), symbol: "checkmark.circle.fill", tone: .waiting)
    case .failed:
      return GameStatus(text: GameL10n.Status.failed, symbol: "exclamationmark.triangle.fill", tone: .problem)
    }
  }
}

extension MeshGame {
  var status: GameStatus {
    switch phase {
    case .placing:
      return GameStatus(text: GameL10n.Status.placeFleet, symbol: "square.grid.3x3", tone: .attention)
    case .inviting:
      return GameStatus(text: GameL10n.Status.waitingForAccept(opponentName), symbol: "hourglass", tone: .waiting)
    case .invited:
      return GameStatus(text: GameL10n.Status.invitesYou(opponentName), symbol: "envelope.badge", tone: .attention)
    case .over:
      return GameStatus(text: ending?.text(opponent: opponentName) ?? GameL10n.Status.over, symbol: "flag.checkered", tone: .done)
    case .playing:
      if let delivery = moveDelivery, delivery != .confirmed || !board.turnIsMine(me) {
        return .delivery(delivery, opponent: opponentName)
      }
      if isMyTurn { return GameStatus(text: GameL10n.Status.yourMove, symbol: "hand.point.right.fill", tone: .attention) }
      return GameStatus(text: GameL10n.Status.theirTurn(opponentName), symbol: "hourglass", tone: .waiting)
    }
  }
}

extension ChessGame {
  var status: GameStatus {
    switch phase {
    case .inviting:
      return GameStatus(text: GameL10n.Status.waitingForAccept(opponentName), symbol: "hourglass", tone: .waiting)
    case .invited:
      return GameStatus(text: GameL10n.Status.invitesYou(opponentName), symbol: "envelope.badge", tone: .attention)
    case .waitingForSetup:
      return GameStatus(text: GameL10n.Status.waitingForSetup(opponentName), symbol: "hourglass", tone: .waiting)
    case .over:
      return GameStatus(text: ending?.text(opponent: opponentName) ?? GameL10n.Status.over, symbol: "flag.checkered", tone: .done)
    case .playing:
      if let delivery = moveDelivery, delivery != .confirmed || !isMyTurn {
        return .delivery(delivery, opponent: opponentName)
      }
      if isMyTurn { return GameStatus(text: GameL10n.Status.yourMove, symbol: "hand.point.right.fill", tone: .attention) }
      return GameStatus(text: GameL10n.Status.theirTurn(opponentName), symbol: "hourglass", tone: .waiting)
    }
  }
}

/// Compact status line with its icon.
struct GameStatusLabel: View {
  let status: GameStatus

  var body: some View {
    Label(status.text, systemImage: status.symbol)
      .foregroundStyle(status.color)
      .fontWeight(status.needsMe ? .semibold : .regular)
  }
}

enum GameNodes {
  static func same(_ a: Data, _ b: Data) -> Bool {
    let length = min(6, a.count, b.count)
    return length > 0 && a.prefix(length) == b.prefix(length)
  }
}

/// Games running with one contact, shown above the DM input bar. Tap to open the board.
struct ActiveGamesStrip: View {
  let contact: ContactDTO

  @State private var meshGames = MeshGamesCoordinator.shared
  @State private var chess = ChessCoordinator.shared

  private struct Row: Identifiable {
    let id: String
    let title: String
    let symbol: String
    let status: GameStatus
    let open: @MainActor () -> Void
  }

  private var rows: [Row] {
    var rows: [Row] = []
    if let game = chess.game, game.phase != .over, GameNodes.same(game.opponentKey, contact.publicKey) {
      rows.append(Row(id: "chess", title: GameL10n.Kind.chess, symbol: "checkerboard.rectangle", status: game.status) {
        chess.isBoardPresented = true
      })
    }
    for game in meshGames.games where game.phase != .over && GameNodes.same(game.opponentKey, contact.publicKey) {
      let id = game.id
      rows.append(Row(id: id, title: game.kind.title, symbol: game.kind.symbol, status: game.status) {
        meshGames.presentedGameID = id
      })
    }
    return rows
  }

  var body: some View {
    let rows = rows
    if !rows.isEmpty {
      VStack(spacing: 4) {
        ForEach(rows) { row in
          Button(action: row.open) {
            HStack(spacing: 8) {
              Image(systemName: row.symbol).foregroundStyle(.secondary)
              Text(row.title).fontWeight(.medium).foregroundStyle(.primary)
              GameStatusLabel(status: row.status).lineLimit(1)
              Spacer(minLength: 0)
              Image(systemName: "chevron.right").font(.caption2).foregroundStyle(.tertiary)
            }
            .font(.footnote)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
              row.status.needsMe ? AnyShapeStyle(row.status.color.opacity(0.15)) : AnyShapeStyle(.thinMaterial),
              in: .capsule
            )
          }
          .buttonStyle(.plain)
        }
      }
      .padding(.horizontal, 12)
      .padding(.top, 4)
    }
  }
}
