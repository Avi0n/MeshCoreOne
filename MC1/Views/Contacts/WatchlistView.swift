import MC1Services
import SwiftUI

/// Shows the last saved readings, then refreshes repeaters with no snapshot or one past the snapshot window.
struct WatchlistView: View {
  @Environment(\.appState) private var appState

  let radioID: UUID
  let contacts: [ContactDTO]
  let pendingRemovalIDs: Set<UUID>
  let coordinator: WatchlistRefreshCoordinator
  let onSelect: (ContactDTO) -> Void
  let onLogIn: (ContactDTO) -> Void

  private var rows: [ContactDTO] {
    WatchlistDirectory.markedRepeaters(
      in: contacts, radioID: radioID, pendingRemovalIDs: pendingRemovalIDs
    )
  }

  private var progressText: String? {
    coordinator.progress.map {
      L10n.Contacts.Contacts.Watchlist.progress(min($0.completed + 1, $0.total), $0.total)
    }
  }

  var body: some View {
    Group {
      if rows.isEmpty {
        ContentUnavailableView {
          Label(L10n.Contacts.Contacts.Watchlist.empty, systemImage: "server.rack")
        } description: {
          Text(L10n.Contacts.Contacts.Watchlist.emptyDescription)
        }
      } else {
        List {
          Section {
            ForEach(rows) { contact in
              VStack(alignment: .leading) {
                Button {
                  onSelect(contact)
                } label: {
                  WatchlistRow(
                    contact: contact,
                    reading: coordinator.batteryReadings[contact.id],
                    isActive: coordinator.activeContactID == contact.id,
                    failed: coordinator.failedContactIDs.contains(contact.id),
                    timedOut: coordinator.timedOutContactIDs.contains(contact.id)
                  )
                }
                .buttonStyle(.plain)
                if coordinator.timedOutContactIDs.contains(contact.id) {
                  Button(L10n.Contacts.Contacts.Watchlist.logInAndRetry) {
                    onLogIn(contact)
                  }
                  .buttonStyle(.borderless)
                  .disabled(appState.connectionState != .ready || coordinator.isRefreshing)
                }
              }
            }
          } header: {
            if let progressText {
              Text(progressText)
            }
          }
        }
      }
    }
    // Keyed on the board's members and readiness only, so a finished refresh does not retrigger itself.
    .task(id: AutoRefreshKey(rowIDs: rows.map(\.id), isReady: appState.connectionState == .ready)) {
      await coordinator.loadReadings(for: rows)
      coordinator.refreshStale(rows)
    }
    .navigationTitle(L10n.Contacts.Contacts.List.watchlist)
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .primaryAction) {
        // One button whose role flips keeps toolbar item identity stable.
        Button {
          if coordinator.isRefreshing {
            coordinator.cancelRefresh()
          } else {
            coordinator.refresh(rows)
          }
        } label: {
          if coordinator.isRefreshing {
            Label(L10n.Localizable.Common.cancel, systemImage: "xmark")
          } else {
            Label(L10n.Contacts.Contacts.Watchlist.refresh, systemImage: "arrow.clockwise")
          }
        }
        .disabled(!coordinator.isRefreshing && appState.connectionState != .ready)
        .accessibilityValue(progressText ?? "")
      }
    }
  }
}

private struct AutoRefreshKey: Equatable {
  let rowIDs: [UUID]
  let isReady: Bool
}

private enum WatchlistRowLayout {
  static let rowSpacing: CGFloat = 4
  static let titleSpacing: CGFloat = 8
}

private struct WatchlistRow: View {
  let contact: ContactDTO
  let reading: WatchlistRefreshCoordinator.BatteryReading?
  let isActive: Bool
  let failed: Bool
  let timedOut: Bool

  var body: some View {
    VStack(alignment: .leading, spacing: WatchlistRowLayout.rowSpacing) {
      HStack(alignment: .firstTextBaseline, spacing: WatchlistRowLayout.titleSpacing) {
        Text(contact.displayName)
          .font(.headline)
          .foregroundStyle(.primary)
        Spacer(minLength: 0)
        if isActive {
          ProgressView()
        } else {
          battery
        }
      }
      TimelineView(.everyMinute) { context in
        VStack(alignment: .leading, spacing: WatchlistRowLayout.rowSpacing) {
          Text(WatchlistReading.detailLine(contact, now: context.date))
            .font(.subheadline)
          Text(WatchlistReading.checkedLine(reading, now: context.date))
            .font(.footnote)
        }
        .foregroundStyle(.secondary)
      }
      if failed {
        Label(
          timedOut ? L10n.Contacts.Contacts.Watchlist.noReply : L10n.Contacts.Contacts.Watchlist.couldNotRefresh,
          systemImage: "exclamationmark.triangle.fill"
        )
        .font(.subheadline)
        .foregroundStyle(.orange)
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .contentShape(.rect)
  }

  @ViewBuilder
  private var battery: some View {
    let text = Text(WatchlistReading.battery(reading, contact: contact))
    if reading != nil {
      text
        .font(.subheadline.monospacedDigit())
    } else {
      text
        .font(.subheadline)
        .foregroundStyle(.secondary)
    }
  }
}
