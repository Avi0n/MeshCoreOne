import SwiftUI
import MC1Services

/// Section for configuring the device's persisted default flood scope (firmware v11+).
///
/// Lets the user choose between ``FloodScope/disabled`` (clear) or one of the
/// radio's already-known regions. New regions are added via Manage Regions.
/// Selections are sent via ``SettingsService/setDefaultFloodScopeVerified(name:)``
/// and the accepted value is cached in ``DeviceDTO/defaultFloodScopeName``.
struct DefaultFloodScopeSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var isApplying = false
    @State private var errorMessage: String?
    @State private var retryAlert = RetryAlertState()
    @State private var isDiscovering = false
    @State private var discoveryMessage: String?
    @State private var discoveryTask: Task<Void, Never>?
    @State private var showingRegionManagement = false

    var body: some View {
        Section {
            disabledRow

            ForEach(sortedKnownRegions, id: \.self) { region in
                scopeRow(region)
            }

            discoveryStatusRow
            discoverButton
            manageRegionsButton
        } header: {
            Text(L10n.Settings.DefaultFloodScope.header)
        } footer: {
            Text(L10n.Settings.DefaultFloodScope.footer)
        }
        .themedRowBackground(theme)
        .radioDisabled(for: appState.connectionState, or: isApplying)
        .errorAlert($errorMessage)
        .retryAlert(retryAlert)
        .onDisappear { discoveryTask?.cancel() }
        .navigationDestination(isPresented: $showingRegionManagement) {
            RegionManagementView(
                knownRegions: appState.connectedDevice?.knownRegions ?? [],
                isDiscovering: $isDiscovering,
                discoveryMessage: $discoveryMessage,
                onRemoveRegion: { region in appState.connectionManager.removeKnownRegion(region) },
                onAddRegion: { region in appState.connectionManager.addKnownRegion(region) },
                onDiscoverTapped: runDiscovery
            )
        }
    }

    // MARK: - Rows

    private var disabledRow: some View {
        Button {
            apply(name: nil)
        } label: {
            row(title: L10n.Settings.DefaultFloodScope.disabled, selected: currentScope == nil)
        }
        .buttonStyle(.plain)
    }

    private func scopeRow(_ region: String) -> some View {
        Button {
            apply(name: region)
        } label: {
            row(title: region, selected: currentScope == region)
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private var discoveryStatusRow: some View {
        if isDiscovering {
            HStack {
                ProgressView()
                Text(L10n.Chats.Chats.ChannelInfo.Region.discovering)
                    .foregroundStyle(.secondary)
            }
        } else if let discoveryMessage {
            Text(discoveryMessage)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var discoverButton: some View {
        Button(
            L10n.Chats.Chats.ChannelInfo.Region.discover,
            systemImage: "antenna.radiowaves.left.and.right",
            action: runDiscovery
        )
        .disabled(isDiscovering)
    }

    private var manageRegionsButton: some View {
        Button(L10n.Chats.Chats.ChannelInfo.Region.manageRegions, systemImage: "list.bullet") {
            showingRegionManagement = true
        }
    }

    private func row(title: String, selected: Bool) -> some View {
        HStack {
            Text(title)
                .foregroundStyle(Color.primary)
            Spacer()
            if selected {
                Image(systemName: "checkmark")
                    .foregroundStyle(.tint)
            }
        }
        .contentShape(.rect)
    }

    // MARK: - Derived state

    private var currentScope: String? {
        appState.connectedDevice?.defaultFloodScopeName
    }

    private var sortedKnownRegions: [String] {
        (appState.connectedDevice?.knownRegions ?? [])
            .sorted { $0.localizedStandardCompare($1) == .orderedAscending }
    }

    // MARK: - Actions

    private func apply(name: String?) {
        isApplying = true
        Task {
            do {
                guard let settingsService = appState.services?.settingsService else {
                    throw ConnectionError.notConnected
                }
                _ = try await settingsService.setDefaultFloodScopeVerified(name: name)
                retryAlert.reset()
            } catch let error as SettingsServiceError where error.isRetryable {
                retryAlert.show(
                    message: error.userFacingMessage,
                    onRetry: { apply(name: name) },
                    onMaxRetriesExceeded: { dismiss() }
                )
            } catch {
                errorMessage = error.userFacingMessage
            }
            isApplying = false
        }
    }

    private func runDiscovery() {
        discoveryTask?.cancel()
        discoveryTask = Task {
            isDiscovering = true
            discoveryMessage = nil
            defer { isDiscovering = false }

            guard let session = appState.services?.session,
                  let contactService = appState.services?.contactService,
                  let radioID = appState.connectedDevice?.radioID else {
                return
            }

            let outcome = await RegionDiscoveryService.discover(
                session: session,
                contactService: contactService,
                dataStore: appState.offlineDataStore,
                radioID: radioID,
                knownRegions: appState.connectedDevice?.knownRegions ?? [],
                supportsAdHocRequest: appState.connectedDevice?.supportsAdHocRepeaterRequest ?? false
            )

            guard !Task.isCancelled else { return }

            switch outcome {
            case .sendFailed:
                break
            case .noRepeatersResponded:
                discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noRepeatersResponded
            case .errorLoadingRepeaters:
                discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errLoadingRepeaters
            case let .completed(newRegions, allRepeatersTableFull):
                if newRegions.isEmpty && allRepeatersTableFull {
                    discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.errRadioContactsFull
                } else if newRegions.isEmpty {
                    discoveryMessage = L10n.Chats.Chats.ChannelInfo.Region.noNewRegions
                } else {
                    for region in newRegions {
                        appState.connectionManager.addKnownRegion(region)
                    }
                }
            }
        }
    }
}
