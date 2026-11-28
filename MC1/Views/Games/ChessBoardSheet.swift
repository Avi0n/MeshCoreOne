import MC1Services
import SwiftUI

/// Chess board for the current game, CoreChess-compatible.
struct ChessBoardSheet: View {
  @State private var coordinator = ChessCoordinator.shared
  @Environment(\.dismiss) private var dismiss
  @State private var selected: ChessPosition.Square?
  @State private var pendingMove: ChessPosition.Move?
  @State private var confirmResign = false
  @State private var showDebug = false

  var body: some View {
    NavigationStack {
      Group {
        if let game = coordinator.game {
          content(game)
        } else {
          ContentUnavailableView(
            GameL10n.Chess.noGame,
            systemImage: "checkerboard.rectangle",
            description: Text(GameL10n.Chess.noGameHint)
          )
        }
      }
      .navigationTitle(coordinator.game.map { GameL10n.Chess.versus($0.opponentName) } ?? GameL10n.Kind.chess)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(GameL10n.Sheet.hide) { dismiss() }
        }
        if let game = coordinator.game {
          ToolbarItem(placement: .primaryAction) {
            Menu {
              Button(GameL10n.Sheet.connectionDetails, systemImage: "antenna.radiowaves.left.and.right") { showDebug = true }
              if game.phase == .playing {
                Button(GameL10n.Chess.offerDraw, systemImage: "equal.circle") { coordinator.offerDraw() }
                  .disabled(game.drawOfferedByMe)
                Button(GameL10n.Sheet.resign, systemImage: "flag", role: .destructive) { confirmResign = true }
              }
              Button(game.phase == .playing ? GameL10n.Chess.abandon : GameL10n.Chess.close, systemImage: "xmark.circle", role: .destructive) {
                coordinator.close()
              }
            } label: {
              Image(systemName: "ellipsis.circle")
            }
          }
        }
      }
      .confirmationDialog(GameL10n.Sheet.resignConfirm, isPresented: $confirmResign, titleVisibility: .visible) {
        Button(GameL10n.Sheet.resign, role: .destructive) { coordinator.resign() }
      }
      .navigationDestination(isPresented: $showDebug) {
        if let game = coordinator.game {
          GameDebugView(title: GameL10n.Kind.chess, summary: debugSummary(game), log: game.log)
        }
      }
    }
    .onAppear { coordinator.clearNotification() }
  }

  private func debugSummary(_ game: ChessGame) -> [(String, String)] {
    [
      (GameL10n.Debug.Row.opponent, "\(game.opponentName) · \(GameDebugView.hex(game.opponentKey.prefix(6)))"),
      (GameL10n.Debug.Row.phase, game.phase.rawValue),
      (GameL10n.Debug.Row.iPlay, game.myColor == .white ? GameL10n.Debug.Value.white : GameL10n.Debug.Value.black),
      (GameL10n.Debug.Row.ply, "\(game.ply)"),
      (GameL10n.Debug.Row.sequence, "\(game.seqOut) / \(game.seqIn)"),
      (GameL10n.Debug.Row.waitingForAck, GameDebugView.yesNo(game.awaitingAck)),
      (GameL10n.Debug.Row.ackFailed, GameDebugView.yesNo(game.ackFailed)),
    ]
  }

  @ViewBuilder
  private func content(_ game: ChessGame) -> some View {
    ScrollView {
      VStack(spacing: 14) {
        statusView(game)
        BoardGrid(
          position: displayPosition(game),
          flipped: game.myColor == .black,
          selected: selected,
          targets: targets(game),
          lastMove: pendingMove ?? game.lastMove,
          inCheck: displayPosition(game).isInCheck(displayPosition(game).turn),
          onTap: { tap($0, game: game) }
        )
        .padding(.horizontal, 8)

        if pendingMove != nil {
          HStack(spacing: 16) {
            Button(L10n.Localizable.Common.cancel) { pendingMove = nil }
              .buttonStyle(.bordered)
            Button {
              if let move = pendingMove {
                coordinator.play(from: move.from, to: move.to)
              }
              pendingMove = nil
            } label: {
              Label(GameL10n.Chess.sendMove(pendingMove?.name ?? ""), systemImage: "arrow.up")
            }
            .buttonStyle(.borderedProminent)
          }
        }

        if game.drawOfferedByOpponent {
          VStack(spacing: 8) {
            Text(GameL10n.Chess.drawOffered(game.opponentName))
            HStack {
              Button(GameL10n.Sheet.decline) { coordinator.answerDraw(false) }.buttonStyle(.bordered)
              Button(GameL10n.Chess.acceptDraw) { coordinator.answerDraw(true) }.buttonStyle(.borderedProminent)
            }
          }
          .padding()
          .background(.thinMaterial, in: .rect(cornerRadius: 12))
        }

        if game.ackFailed {
          Button(GameL10n.Sheet.resend, systemImage: "arrow.clockwise") { coordinator.resend() }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
        } else if game.awaitingAck {
          Button(GameL10n.Sheet.resend, systemImage: "arrow.clockwise") { coordinator.resend() }
            .buttonStyle(.bordered)
        }

        if game.phase == .invited {
          HStack {
            Button(GameL10n.Sheet.decline) { coordinator.decline() }.buttonStyle(.bordered)
            Button(GameL10n.Sheet.play) { coordinator.accept() }.buttonStyle(.borderedProminent)
          }
        }

        if !game.history.isEmpty {
          moveList(game.history)
        }
      }
      .padding(.vertical)
    }
  }

  private func statusView(_ game: ChessGame) -> some View {
    VStack(spacing: 4) {
      Text(statusText(game))
        .font(.headline)
        .multilineTextAlignment(.center)
      if game.phase == .playing, let delivery = game.moveDelivery {
        GameStatusLabel(status: .delivery(delivery, opponent: game.opponentName))
          .font(.footnote)
      }
      if let warning = game.warning {
        Text(warning.text(opponent: game.opponentName))
          .font(.footnote)
          .foregroundStyle(.orange)
          .multilineTextAlignment(.center)
      }
      Text(game.myColor == .white ? GameL10n.Chess.playingWhite : GameL10n.Chess.playingBlack)
        .font(.caption)
        .foregroundStyle(.secondary)
    }
    .padding(.horizontal)
  }

  private func statusText(_ game: ChessGame) -> String {
    switch game.phase {
    case .inviting: return GameL10n.Status.waitingForAccept(game.opponentName)
    case .invited: return GameL10n.Chess.invitesYou(game.opponentName)
    case .waitingForSetup: return GameL10n.Status.waitingForSetup(game.opponentName)
    case .over: return game.endingText
    case .playing:
      if game.awaitingAck { return GameL10n.Sheet.moveSent }
      let turn = game.isMyTurn ? GameL10n.Sheet.yourMove : GameL10n.Sheet.thinking(game.opponentName)
      return game.position.isInCheck(game.position.turn) ? turn + " " + GameL10n.Chess.check : turn
    }
  }

  private func displayPosition(_ game: ChessGame) -> ChessPosition {
    pendingMove.map { game.position.applying($0) } ?? game.position
  }

  private func targets(_ game: ChessGame) -> Set<ChessPosition.Square> {
    guard let selected, pendingMove == nil else { return [] }
    return Set(game.position.legalMoves(from: selected).map(\.to))
  }

  private func tap(_ square: ChessPosition.Square, game: ChessGame) {
    guard game.isMyTurn, !game.awaitingAck, pendingMove == nil else { return }
    if let selected, let move = game.position.legalMove(from: selected, to: square) {
      pendingMove = move
      self.selected = nil
      return
    }
    if let piece = game.position[square], ChessPosition.color(of: piece) == game.myColor {
      selected = square
    } else {
      selected = nil
    }
  }

  private func moveList(_ history: [String]) -> some View {
    let pairs = stride(from: 0, to: history.count, by: 2).map { index in
      "\(index / 2 + 1). \(history[index])" + (index + 1 < history.count ? "  \(history[index + 1])" : "")
    }
    return VStack(alignment: .leading, spacing: 2) {
      Text(GameL10n.Chess.moves).font(.caption).foregroundStyle(.secondary)
      ForEach(pairs, id: \.self) { Text($0).font(.caption.monospaced()) }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.horizontal)
  }
}

private struct BoardGrid: View {
  let position: ChessPosition
  let flipped: Bool
  let selected: ChessPosition.Square?
  let targets: Set<ChessPosition.Square>
  let lastMove: ChessPosition.Move?
  let inCheck: Bool
  let onTap: (ChessPosition.Square) -> Void

  /// Vector piece set "cburnett" by Colin M.L. Burnett (Wikimedia Commons,
  /// GPLv2+/BSD/GFDL), in the asset catalog as `chess-<w|b><k|q|r|b|n|p>`.
  /// Font glyphs drew the two sides with different shapes and the pawn as an emoji.
  private func pieceView(_ piece: Character, size: CGFloat) -> some View {
    let side = ChessPosition.color(of: piece) == .white ? "w" : "b"
    return Image("chess-\(side)\(piece.lowercased())")
      .resizable()
      .interpolation(.high)
      .scaledToFit()
      .frame(width: size * 0.9, height: size * 0.9)
  }

  var body: some View {
    GeometryReader { proxy in
      let size = min(proxy.size.width, proxy.size.height) / 8
      VStack(spacing: 0) {
        ForEach(0..<8, id: \.self) { displayRow in
          HStack(spacing: 0) {
            ForEach(0..<8, id: \.self) { displayCol in
              let square = ChessPosition.Square(
                row: flipped ? 7 - displayRow : displayRow,
                col: flipped ? 7 - displayCol : displayCol
              )
              cell(square, size: size)
            }
          }
        }
      }
    }
    .aspectRatio(1, contentMode: .fit)
    .clipShape(.rect(cornerRadius: 6))
  }

  private func cell(_ square: ChessPosition.Square, size: CGFloat) -> some View {
    let light = (square.row + square.col) % 2 == 0
    let piece = position[square]
    let isKingInCheck = inCheck && piece.map { $0.uppercased() == "K" && ChessPosition.color(of: $0) == position.turn } == true
    return ZStack {
      Rectangle().fill(light ? Color(red: 0.93, green: 0.85, blue: 0.71) : Color(red: 0.71, green: 0.53, blue: 0.39))
      if lastMove?.from == square || lastMove?.to == square {
        Rectangle().fill(Color.yellow.opacity(0.35))
      }
      if selected == square {
        Rectangle().fill(Color.blue.opacity(0.35))
      }
      if isKingInCheck {
        Rectangle().fill(Color.red.opacity(0.45))
      }
      if let piece {
        pieceView(piece, size: size)
      }
      if targets.contains(square) {
        Circle()
          .fill(Color.black.opacity(piece == nil ? 0.25 : 0))
          .strokeBorder(Color.black.opacity(piece == nil ? 0 : 0.35), lineWidth: 3)
          .frame(width: size * (piece == nil ? 0.3 : 0.9), height: size * (piece == nil ? 0.3 : 0.9))
      }
    }
    .frame(width: size, height: size)
    .contentShape(Rectangle())
    .onTapGesture { onTap(square) }
    .accessibilityLabel(square.name + (piece.map { " \($0)" } ?? ""))
  }
}
