import MC1Services
import SwiftUI

/// "Games" from a DM's + menu: start a game with this contact or reopen a running one.
struct GamesPickerSheet: View {
  let contact: ContactDTO

  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState
  @State private var tallies: [String: GameTally] = [:]
  @State private var games = MeshGamesCoordinator.shared
  @State private var chess = ChessCoordinator.shared

  var body: some View {
    NavigationStack {
      List {
        Section {
          Button {
            afterDismiss { chess.invite(contact) }
          } label: {
            Label(GameL10n.Picker.chess, systemImage: "checkerboard.rectangle")
          }
          ForEach(MeshGameKind.allCases) { kind in
            Button {
              afterDismiss { games.invite(kind, contact: contact) }
            } label: {
              Label(kind.title, systemImage: kind.symbol)
            }
          }
        } header: {
          Text(GameL10n.Picker.invite(contact.displayName))
        } footer: {
          Text(GameL10n.Picker.footer)
        }

        if !scoreRows.isEmpty {
          Section(GameL10n.Picker.record(contact.displayName)) {
            ForEach(scoreRows, id: \.title) { row in
              LabeledContent(row.title) {
                Text("\(row.tally.wins)–\(row.tally.draws)–\(row.tally.losses)")
                  .monospacedDigit()
              }
              .accessibilityValue(GameL10n.Picker.recordAccessibility(row.tally.wins, row.tally.draws, row.tally.losses))
            }
          }
        }

        if !games.games.isEmpty || chess.game != nil {
          Section(GameL10n.Picker.yourGames) {
            if let game = chess.game {
              Button {
                afterDismiss { chess.isBoardPresented = true }
              } label: {
                GameRow(title: GameL10n.Kind.chess, opponent: game.opponentName, status: game.status)
              }
            }
            ForEach(games.games) { game in
              Button {
                afterDismiss { games.presentedGameID = game.id }
              } label: {
                GameRow(title: game.kind.title, opponent: game.opponentName, status: game.status)
              }
              .swipeActions {
                Button(GameL10n.Picker.remove, role: .destructive) { games.remove(game.id) }
              }
            }
          }
        }
      }
      .task {
        tallies = await appState.services?.gameStats.tallies(opponentKey: contact.publicKey) ?? [:]
      }
      .navigationTitle(GameL10n.Picker.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(GameL10n.Picker.close) { dismiss() }
        }
      }
    }
  }
}

extension GamesPickerSheet {
  /// Played games against this contact, as wins–draws–losses.
  fileprivate var scoreRows: [(title: String, tally: GameTally)] {
    let games = [(GameStatsStore.chess, GameL10n.Kind.chess)] + MeshGameKind.allCases.map { ($0.rawValue, $0.title) }
    return games.compactMap { key, title in
      guard let tally = tallies[key], tally.played > 0 else { return nil }
      return (title, tally)
    }
  }

  /// Runs `action` once this sheet is gone, so the game sheet can be presented from the root.
  fileprivate func afterDismiss(_ action: @escaping @MainActor () -> Void) {
    dismiss()
    Task { @MainActor in
      try? await Task.sleep(for: .milliseconds(450))
      action()
    }
  }
}

private struct GameRow: View {
  let title: String
  let opponent: String
  let status: GameStatus

  var body: some View {
    VStack(alignment: .leading, spacing: 2) {
      Text(GameL10n.gameWithOpponent(title, opponent)).foregroundStyle(.primary)
      GameStatusLabel(status: status).font(.caption)
    }
  }
}
