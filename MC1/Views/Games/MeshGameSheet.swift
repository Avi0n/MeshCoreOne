import MC1Services
import SwiftUI

/// Board for one battleship game.
struct MeshGameSheet: View {
  let gameID: String

  @Environment(\.dismiss) private var dismiss
  @State private var coordinator = MeshGamesCoordinator.shared
  /// The move the player picked, shown before it is sent.
  @State private var pendingMove: String?
  @State private var confirmResign = false
  @State private var showDebug = false
  /// Battleship fleet being placed before the invite or accept commits to it.
  @State private var fleetDraft: [Battleship.Ship]?

  var body: some View {
    NavigationStack {
      Group {
        if let game = coordinator.game(gameID) {
          ScrollView {
            VStack(spacing: 14) {
              status(game)
              board(game)
              controls(game)
            }
            .padding(.vertical)
          }
          .navigationTitle(GameL10n.gameWithOpponent(game.kind.title, game.opponentName))
        } else {
          ContentUnavailableView(GameL10n.Sheet.ended, systemImage: "gamecontroller")
        }
      }
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(GameL10n.Sheet.hide) { dismiss() }
        }
        if let game = coordinator.game(gameID) {
          ToolbarItem(placement: .primaryAction) {
            Menu {
              Button(GameL10n.Sheet.connectionDetails, systemImage: "antenna.radiowaves.left.and.right") { showDebug = true }
              if game.phase == .playing {
                Button(GameL10n.Sheet.resign, systemImage: "flag", role: .destructive) { confirmResign = true }
              }
              Button(GameL10n.Sheet.remove, systemImage: "trash", role: .destructive) {
                coordinator.remove(gameID)
                dismiss()
              }
            } label: {
              Image(systemName: "ellipsis.circle")
            }
          }
        }
      }
      .confirmationDialog(GameL10n.Sheet.resignConfirm, isPresented: $confirmResign, titleVisibility: .visible) {
        Button(GameL10n.Sheet.resign, role: .destructive) { coordinator.resign(gameID) }
      }
      .navigationDestination(isPresented: $showDebug) {
        if let game = coordinator.game(gameID) {
          GameDebugView(title: game.kind.title, summary: debugSummary(game), log: game.log)
        }
      }
    }
    .onAppear { coordinator.clearNotification(gameID) }
  }

  private func debugSummary(_ game: MeshGame) -> [(String, String)] {
    [
      (GameL10n.Debug.Row.game, "\(game.kind.title) · \(game.id)"),
      (GameL10n.Debug.Row.opponent, "\(game.opponentName) · \(GameDebugView.hex(game.opponentKey.prefix(6)))"),
      (GameL10n.Debug.Row.phase, game.phase.rawValue),
      (GameL10n.Debug.Row.iPlay, game.me == 0 ? GameL10n.Debug.Value.first : GameL10n.Debug.Value.second),
      (GameL10n.Debug.Row.moves, "\(game.moves.count)"),
      (GameL10n.Debug.Row.sequence, "\(game.seqOut) / \(game.seqIn)"),
      (GameL10n.Debug.Row.waitingForAck, GameDebugView.yesNo(game.awaitingAck)),
      (GameL10n.Debug.Row.ackFailed, GameDebugView.yesNo(game.ackFailed)),
      (GameL10n.Debug.Row.updated, game.updatedAt.formatted(date: .abbreviated, time: .standard)),
    ]
  }

  // MARK: Status and controls

  private func status(_ game: MeshGame) -> some View {
    VStack(spacing: 4) {
      Text(statusText(game))
        .font(.headline)
        .multilineTextAlignment(.center)
      if game.phase == .playing, let delivery = game.moveDelivery {
        GameStatusLabel(status: .delivery(delivery, opponent: game.opponentName))
          .font(.footnote)
      }
      if let warning = game.warning {
        Text(warning.text(opponent: game.opponentName)).font(.footnote).foregroundStyle(.orange).multilineTextAlignment(.center)
      }
      if let verdict = secretVerdict(game.board) {
        switch verdict {
        case .honest:
          Label(GameL10n.Battleship.verified, systemImage: "checkmark.seal")
            .font(.footnote).foregroundStyle(.green)
        case let .cheated(reason):
          Label(GameL10n.Battleship.cheating(reason.text), systemImage: "exclamationmark.triangle")
            .font(.footnote).foregroundStyle(.red)
        }
      }
    }
    .padding(.horizontal)
  }

  /// The check of the opponent's revealed secret: nil until it arrives, or for open games.
  private enum SecretVerdict {
    case honest
    case cheated(Battleship.CheatReason)
  }

  private func secretVerdict(_ board: MeshGameBoard) -> SecretVerdict? {
    switch board {
    case let .battleship(sea):
      switch sea.verdict {
      case .honest: .honest
      case let .cheated(reason): .cheated(reason)
      case nil: nil
      }
    }
  }

  private func statusText(_ game: MeshGame) -> String {
    switch game.phase {
    case .placing: return GameL10n.Battleship.placeAndInvite
    case .inviting: return GameL10n.Status.waitingForAccept(game.opponentName)
    case .invited: return GameL10n.Sheet.invitesYouTo(game.opponentName, game.kind.title)
    case .over: return game.endingText
    case .playing:
      if game.awaitingAck { return GameL10n.Sheet.moveSent }
      return game.board.turnIsMine(game.me) ? GameL10n.Sheet.yourMove : GameL10n.Sheet.thinking(game.opponentName)
    }
  }

  @ViewBuilder
  private func controls(_ game: MeshGame) -> some View {
    if game.phase == .placing {
      Button(GameL10n.Battleship.sendInvite, systemImage: "paperplane") { coordinator.sendInvite(gameID, fleet: fleetDraft) }
        .buttonStyle(.borderedProminent)
    }
    if game.phase == .invited {
      HStack {
        Button(GameL10n.Sheet.decline) { coordinator.decline(gameID) }.buttonStyle(.bordered)
        Button(GameL10n.Sheet.play) { coordinator.accept(gameID, fleet: fleetDraft) }.buttonStyle(.borderedProminent)
      }
    }
    if let pendingMove {
      HStack(spacing: 16) {
        Button(L10n.Localizable.Common.cancel) {
          self.pendingMove = nil
        }
        .buttonStyle(.bordered)
        Button {
          coordinator.play(gameID, move: pendingMove)
          self.pendingMove = nil
        } label: {
          Label(GameL10n.Sheet.send(moveLabel(pendingMove)), systemImage: "arrow.up")
        }
        .buttonStyle(.borderedProminent)
      }
    }
    if game.ackFailed {
      Button(GameL10n.Sheet.resend, systemImage: "arrow.clockwise") { coordinator.resend(gameID) }
        .buttonStyle(.borderedProminent)
        .tint(.orange)
    } else if game.awaitingAck {
      Button(GameL10n.Sheet.resend, systemImage: "arrow.clockwise") { coordinator.resend(gameID) }
        .buttonStyle(.bordered)
    }
    if case let .battleship(sea) = game.board, game.isMyTurn,
       let shot = sea.lastOpponentShot, let final = sea.losingAnswer(to: shot) {
      Button(GameL10n.Battleship.confirmLoss, systemImage: "flag.fill") {
        coordinator.play(gameID, move: final)
      }
      .buttonStyle(.borderedProminent)
      .tint(.red)
    }
  }

  private func moveLabel(_ move: String) -> String {
    if let shot = move.split(separator: ";").first(where: { $0.hasPrefix("F:") }) { return String(shot.dropFirst(2)) }
    return move
  }

  // MARK: Boards

  @ViewBuilder
  private func board(_ game: MeshGame) -> some View {
    let canMove = game.isMyTurn && pendingMove == nil
    switch game.board {
    case let .battleship(sea) where game.phase == .placing || game.phase == .invited:
      FleetEditor(ships: Binding(
        get: { fleetDraft ?? sea.ships },
        set: { fleetDraft = $0 }
      ))
      .padding(.horizontal)
    case let .battleship(sea):
      BattleshipBoards(sea: sea, preview: pendingMove.flatMap(previewShot)) { cell in
        guard canMove else { return }
        // Our fleet is gone: the only move left is confirming it (button below).
        if let shot = sea.lastOpponentShot, sea.losingAnswer(to: shot) != nil { return }
        guard let text = sea.moveText(shootingAt: cell, answering: sea.lastOpponentShot) else { return }
        pendingMove = text
      }
    }
  }

  private func previewShot(_ move: String) -> GridCell? {
    move.split(separator: ";").first { $0.hasPrefix("F:") }.flatMap { Battleship.parseCell(String($0.dropFirst(2))) }
  }
}

extension MeshGameBoard {
  func turnIsMine(_ me: Int) -> Bool {
    switch self {
    case let .battleship(g): g.turn == me
    }
  }
}

// MARK: - Battleship

private struct BattleshipBoards: View {
  let sea: Battleship
  let preview: GridCell?
  let onTarget: (GridCell) -> Void

  var body: some View {
    VStack(spacing: 12) {
      Text(GameL10n.Battleship.enemyWaters(sea.opponentShipsSunk)).font(.caption).foregroundStyle(.secondary)
      grid(target: true)
      Text(GameL10n.Battleship.yourFleet(sea.shipsLeft)).font(.caption).foregroundStyle(.secondary)
      grid(target: false)
        .frame(maxWidth: 240)
    }
    .padding(.horizontal)
  }

  private func grid(target: Bool) -> some View {
    let shipCells = Set(sea.ships.flatMap(\.cells))
    return GeometryReader { proxy in
      let size = proxy.size.width / CGFloat(Battleship.size)
      VStack(spacing: 0) {
        ForEach(0..<Battleship.size, id: \.self) { row in
          HStack(spacing: 0) {
            ForEach(0..<Battleship.size, id: \.self) { col in
              let cell = GridCell(row: row, col: col)
              ZStack {
                Rectangle().fill(Color.blue.opacity(0.15))
                Rectangle().stroke(Color.blue.opacity(0.3), lineWidth: 0.5)
                if target {
                  mark(sea.outgoing[cell], size: size)
                  if sea.pendingShot == cell || preview == cell {
                    Image(systemName: "scope").font(.system(size: size * 0.6)).foregroundStyle(.orange)
                  }
                } else {
                  if shipCells.contains(cell) { Rectangle().fill(Color.gray.opacity(0.7)).padding(1) }
                  mark(sea.incoming[cell], size: size)
                  if sea.lastOpponentShot == cell {
                    Circle().stroke(Color.orange, lineWidth: 2).padding(2)
                  }
                }
              }
              .frame(width: size, height: size)
              .contentShape(Rectangle())
              .onTapGesture { if target { onTarget(cell) } }
            }
          }
        }
      }
    }
    .aspectRatio(1, contentMode: .fit)
  }

  @ViewBuilder
  private func mark(_ mark: Battleship.Mark?, size: CGFloat) -> some View {
    switch mark {
    case .miss?: Circle().fill(Color.white.opacity(0.8)).frame(width: size * 0.25, height: size * 0.25)
    case .hit?: Image(systemName: "xmark").font(.system(size: size * 0.6, weight: .bold)).foregroundStyle(.red)
    case .sunk?: Rectangle().fill(Color.red.opacity(0.7)).padding(1)
    case nil: EmptyView()
    }
  }
}

// MARK: - Fleet placement

/// Places the battleship fleet: tap a ship to pick it, tap water to move it there,
/// rotate it, or shuffle the whole fleet. Ships may not touch, even diagonally.
private struct FleetEditor: View {
  @Binding var ships: [Battleship.Ship]
  @State private var selected: Int?

  var body: some View {
    VStack(spacing: 10) {
      Text(selected == nil ? GameL10n.Battleship.pickShip : GameL10n.Battleship.moveShip)
        .font(.caption).foregroundStyle(.secondary)
      grid
      HStack(spacing: 12) {
        Button(GameL10n.Battleship.rotate, systemImage: "rotate.right") { rotateSelected() }
          .buttonStyle(.bordered)
          .disabled(selected == nil)
        Button(GameL10n.Battleship.shuffle, systemImage: "shuffle") {
          ships = Battleship.randomFleet()
          selected = nil
        }
        .buttonStyle(.bordered)
      }
    }
  }

  /// With a ship picked: the water squares its bow can move to, keeping its direction.
  private var validBows: Set<GridCell>? {
    guard let selected else { return nil }
    let ship = ships[selected]
    var others = ships
    others.remove(at: selected)
    var spots = Set<GridCell>()
    for row in 0..<Battleship.size {
      for col in 0..<Battleship.size {
        let origin = GridCell(row: row, col: col)
        if Battleship.fits(Battleship.Ship(origin: origin, horizontal: ship.horizontal, length: ship.length), among: others) {
          spots.insert(origin)
        }
      }
    }
    return spots
  }

  private var grid: some View {
    let spots = validBows
    return GeometryReader { proxy in
      let size = proxy.size.width / CGFloat(Battleship.size)
      VStack(spacing: 0) {
        ForEach(0..<Battleship.size, id: \.self) { row in
          HStack(spacing: 0) {
            ForEach(0..<Battleship.size, id: \.self) { col in
              let cell = GridCell(row: row, col: col)
              let owner = ships.firstIndex { $0.cells.contains(cell) }
              ZStack {
                Rectangle().fill(Color.blue.opacity(0.15))
                if let spots, owner == nil {
                  DiagonalHatch(spacing: max(4, size / 4))
                    .stroke(spots.contains(cell) ? Color.green : Color.red.opacity(0.6), lineWidth: 1.2)
                    .clipped()
                }
                Rectangle().stroke(Color.blue.opacity(0.3), lineWidth: 0.5)
                if let owner {
                  Rectangle()
                    .fill(owner == selected ? Color.orange : Color.gray.opacity(0.75))
                    .padding(1)
                }
              }
              .frame(width: size, height: size)
              .contentShape(Rectangle())
              .onTapGesture { tap(cell, owner: owner) }
            }
          }
        }
      }
    }
    .aspectRatio(1, contentMode: .fit)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(GameL10n.Battleship.fleetAccessibility(Battleship.encode(ships)))
  }

  private func tap(_ cell: GridCell, owner: Int?) {
    if let owner {
      selected = owner == selected ? nil : owner
      return
    }
    guard let selected else { return }
    let ship = ships[selected]
    place(Battleship.Ship(origin: cell, horizontal: ship.horizontal, length: ship.length), at: selected)
  }

  private func rotateSelected() {
    guard let selected else { return }
    let ship = ships[selected]
    place(Battleship.Ship(origin: ship.origin, horizontal: !ship.horizontal, length: ship.length), at: selected)
  }

  /// Moves ship `index` if the new spot is on the board and clear of the others.
  private func place(_ ship: Battleship.Ship, at index: Int) {
    var others = ships
    others.remove(at: index)
    guard Battleship.fits(ship, among: others) else { return }
    ships[index] = ship
  }
}

/// Parallel lines from bottom-left to top-right, `spacing` apart.
private struct DiagonalHatch: Shape {
  let spacing: CGFloat

  func path(in rect: CGRect) -> Path {
    var path = Path()
    var offset = -rect.height
    while offset < rect.width {
      path.move(to: CGPoint(x: rect.minX + offset, y: rect.maxY))
      path.addLine(to: CGPoint(x: rect.minX + offset + rect.height, y: rect.minY))
      offset += spacing
    }
    return path
  }
}
