import MC1Services
import SwiftUI

/// Display view for room server stats, telemetry, and battery curve
struct RoomStatusView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme
    @Environment(\.dismiss) private var dismiss

    let session: RemoteNodeSessionDTO
    @State private var viewModel = RoomStatusViewModel()

    var body: some View {
        NavigationStack {
            List {
                makeHeaderSection()
                makeStatusSection()
                makeTelemetrySection()
                makeBatteryCurveSection()
            }
            .themedCanvas(theme)
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(L10n.RemoteNodes.RemoteNodes.RoomStatus.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.RemoteNodes.RemoteNodes.done) { dismiss() }
                }

                ToolbarItem(placement: .primaryAction) {
                    Button {
                        refresh()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Status.refresh)
                    .radioDisabled(
                        for: appState.connectionState,
                        or: viewModel.helper.isLoadingStatus || viewModel.helper.isLoadingTelemetry
                    )
                }

                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button(L10n.RemoteNodes.RemoteNodes.done) {
                        UIApplication.shared.sendAction(
                            #selector(UIResponder.resignFirstResponder),
                            to: nil,
                            from: nil,
                            for: nil
                        )
                    }
                }
            }
            .task {
                viewModel.configure(appState: appState)
                await viewModel.registerHandlers(appState: appState)

                // Only request status on first load; user can refresh via toolbar/pull-to-refresh
                if viewModel.helper.status == nil {
                    await viewModel.requestStatus(for: session)
                }

                // Pre-load OCV settings
                if let radioID = appState.connectedDevice?.radioID {
                    await viewModel.helper.loadOCVSettings(publicKey: session.publicKey, radioID: radioID)
                }
            }
            .refreshable {
                await viewModel.requestStatus(for: session)
                // Refresh telemetry only if already loaded
                if viewModel.helper.telemetryLoaded {
                    await viewModel.requestTelemetry(for: session)
                }
            }
        }
        .presentationDetents([.large])
    }

    // MARK: - Subviews

    private func makeHeaderSection() -> some View {
        NodeStatusHeaderSection(session: session)
    }

    private func makeStatusSection() -> some View {
        RoomStatusSection(viewModel: viewModel)
    }

    private func makeTelemetrySection() -> some View {
        NodeTelemetryDisclosureSection(helper: viewModel.helper) {
            await viewModel.requestTelemetry(for: session)
        }
    }

    private func makeBatteryCurveSection() -> some View {
        NodeBatteryCurveDisclosureSection(
            helper: viewModel.helper,
            session: session,
            connectionState: appState.connectionState,
            connectedDeviceID: appState.connectedDevice?.radioID
        )
    }

    // MARK: - Actions

    private func refresh() {
        Task {
            await viewModel.requestStatus(for: session)
            // Refresh telemetry only if already loaded
            if viewModel.helper.telemetryLoaded {
                await viewModel.requestTelemetry(for: session)
            }
        }
    }
}

// MARK: - Status Section

private struct RoomStatusSection: View {
    let viewModel: RoomStatusViewModel

    var body: some View {
        NodeStatusSection(helper: viewModel.helper) {
            RoomStatusRows(viewModel: viewModel)
        }
    }
}

// MARK: - Status Rows

private struct RoomStatusRows: View {
    let viewModel: RoomStatusViewModel

    var body: some View {
        NodeCommonStatusRows(helper: viewModel.helper)
        LabeledContent(L10n.RemoteNodes.RemoteNodes.RoomStatus.postsReceived, value: viewModel.postsReceivedDisplay)
        LabeledContent(L10n.RemoteNodes.RemoteNodes.RoomStatus.postsPushed, value: viewModel.postsPushedDisplay)
    }
}

#Preview {
    RoomStatusView(
        session: RemoteNodeSessionDTO(
            radioID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test Room",
            role: .roomServer,
            isConnected: true,
            permissionLevel: .admin
        )
    )
    .environment(\.appState, AppState())
}
