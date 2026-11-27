import MC1Services
import SwiftUI

/// Shows the last saved readings and refreshes repeaters on demand.
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

  private var canStartRefresh: Bool {
    appState.connectionState == .ready && !coordinator.isRefreshing
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
              VStack(alignment: .leading, spacing: WatchlistRowLayout.footerSpacing) {
                Button {
                  onSelect(contact)
                } label: {
                  let isActive = coordinator.activeContactID == contact.id
                  WatchlistRow(
                    contact: contact,
                    reading: coordinator.batteryReadings[contact.id],
                    isActive: isActive,
                    activePhase: isActive ? coordinator.activePhase : nil,
                    phaseDeadline: isActive ? coordinator.phaseDeadline : nil
                  )
                }
                .buttonStyle(.plain)
                if coordinator.failedContactIDs.contains(contact.id) {
                  WatchlistFailureFooter(
                    timedOut: coordinator.timedOutContactIDs.contains(contact.id),
                    isActionDisabled: appState.connectionState != .ready || coordinator.isRefreshing,
                    onLogIn: { onLogIn(contact) }
                  )
                }
              }
              .contextMenu {
                Button {
                  coordinator.refresh([contact])
                } label: {
                  Label(L10n.Contacts.Contacts.Watchlist.refresh, systemImage: "arrow.clockwise")
                }
                .disabled(!canStartRefresh)
              }
              .swipeActions(edge: .leading, allowsFullSwipe: true) {
                if canStartRefresh {
                  Button {
                    coordinator.refresh([contact])
                  } label: {
                    Label(L10n.Contacts.Contacts.Watchlist.refresh, systemImage: "arrow.clockwise")
                  }
                  .tint(.accentColor)
                }
              }
            }
          } header: {
            if let progress = coordinator.progress, let progressText {
              VStack(alignment: .leading, spacing: WatchlistRowLayout.progressSpacing) {
                Text(progressText)
                ProgressView(value: Double(progress.completed), total: Double(max(progress.total, 1)))
              }
            }
          }
        }
      }
    }
    .task(id: rows.map(\.id)) {
      await coordinator.loadReadings(for: rows)
    }
    .onChange(of: coordinator.activePhase) { _, newPhase in
      guard newPhase == .retryingViaFlood else { return }
      AccessibilityNotification.Announcement(L10n.Contacts.Contacts.Watchlist.phaseRetryingViaFlood).post()
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
        .disabled(!coordinator.isRefreshing && (appState.connectionState != .ready || rows.isEmpty))
        .accessibilityValue(progressText ?? "")
      }
    }
  }
}

private enum WatchlistRowLayout {
  static let rowSpacing: CGFloat = 4
  static let titleSpacing: CGFloat = 8
  static let inlineSpacing: CGFloat = 6
  static let progressSpacing: CGFloat = 6
  static let footerSpacing: CGFloat = 8
}

private struct WatchlistFailureFooter: View {
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize
  let timedOut: Bool
  let isActionDisabled: Bool
  let onLogIn: () -> Void

  private var statusLabel: some View {
    Label(
      timedOut ? L10n.Contacts.Contacts.Watchlist.noReply : L10n.Contacts.Contacts.Watchlist.couldNotRefresh,
      systemImage: "exclamationmark.triangle.fill"
    )
    .labelStyle(.titleAndIcon)
    .font(.footnote)
    .foregroundStyle(.orange)
  }

  private var logInButton: some View {
    Button(L10n.Contacts.Contacts.Watchlist.logInAndRetry, action: onLogIn)
      .buttonStyle(.bordered)
      .buttonBorderShape(.capsule)
      .controlSize(.small)
      .disabled(isActionDisabled)
  }

  var body: some View {
    VStack(alignment: .leading, spacing: WatchlistRowLayout.footerSpacing) {
      Divider()
      if timedOut {
        if dynamicTypeSize.isAccessibilitySize {
          VStack(alignment: .leading, spacing: WatchlistRowLayout.inlineSpacing) {
            statusLabel
            logInButton
          }
        } else {
          HStack(spacing: WatchlistRowLayout.titleSpacing) {
            statusLabel
            Spacer(minLength: 0)
            logInButton
          }
        }
      } else {
        statusLabel
      }
    }
  }
}

private struct CountdownSchedule: TimelineSchedule {
  let deadline: Date
  let isPaused: Bool

  func entries(from startDate: Date, mode: TimelineScheduleMode) -> [Date] {
    guard !isPaused else { return [startDate] }
    let remaining = Int(ceil(deadline.timeIntervalSince(startDate)))
    guard remaining > 0 else { return [startDate] }
    return (0...remaining).map { startDate.addingTimeInterval(TimeInterval($0)) }
  }
}

private struct WatchlistRow: View {
  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  let contact: ContactDTO
  let reading: WatchlistRefreshCoordinator.BatteryReading?
  let isActive: Bool
  let activePhase: WatchlistRefreshCoordinator.RefreshPhase?
  let phaseDeadline: Date?

  var body: some View {
    VStack(alignment: .leading, spacing: WatchlistRowLayout.rowSpacing) {
      HStack(alignment: .firstTextBaseline, spacing: WatchlistRowLayout.titleSpacing) {
        Text(contact.displayName)
          .font(.headline)
          .foregroundStyle(.primary)
        Spacer(minLength: 0)
        battery
      }
      TimelineView(ForegroundMinuteSchedule(isPaused: scenePhase == .background)) { context in
        VStack(alignment: .leading, spacing: WatchlistRowLayout.rowSpacing) {
          Text(WatchlistReading.detailLine(contact, now: context.date))
            .font(.subheadline)
          HStack(spacing: WatchlistRowLayout.inlineSpacing) {
            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
              .font(.caption)
              .accessibilityHidden(true)
            Text(WatchlistReading.pathText(contact))
              .font(.caption.monospaced())
              .lineLimit(nil)
          }
          .accessibilityElement(children: .combine)
          .accessibilityLabel(WatchlistReading.pathAccessibilityLabel(contact))
          if !isActive {
            Text(WatchlistReading.checkedLine(reading, now: context.date))
              .font(.footnote)
          }
        }
        .foregroundStyle(.secondary)
      }
      if isActive {
        HStack(spacing: WatchlistRowLayout.inlineSpacing) {
          ProgressView()
            .controlSize(.mini)
          Text(WatchlistReading.phaseText(activePhase ?? .checkingStatus))
            .font(.footnote)
            .foregroundStyle(activePhase == .retryingViaFlood ? .orange : .secondary)
          Spacer(minLength: 0)
          if let phaseDeadline {
            TimelineView(CountdownSchedule(deadline: phaseDeadline, isPaused: scenePhase == .background)) { context in
              if let remaining = WatchlistReading.remainingSeconds(until: phaseDeadline, at: context.date) {
                Text(WatchlistReading.countdownText(seconds: remaining))
                  .font(.footnote.monospacedDigit())
                  .foregroundStyle(.secondary)
                  .contentTransition(.numericText(countsDown: true))
                  .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Auth.secondsRemaining(remaining))
              }
            }
          }
        }
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .contentShape(.rect)
    .animation(reduceMotion ? nil : .default, value: activePhase)
  }

  @ViewBuilder
  private var battery: some View {
    if let batteryText = WatchlistReading.battery(reading, contact: contact) {
      Text(batteryText)
        .font(.subheadline.monospacedDigit())
        .foregroundStyle(isActive ? .secondary : .primary)
    }
  }
}
