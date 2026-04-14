import MC1Services
import SwiftUI

struct NodeTelemetryView: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss

    let contact: ContactDTO
    @State private var viewModel = NodeTelemetryViewModel()

    var body: some View {
        NavigationStack {
            List {
                NodeTelemetryDisclosureSection(helper: viewModel.helper) {
                    await viewModel.requestTelemetry()
                }
            }
            .navigationTitle(contact.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.RemoteNodes.RemoteNodes.done) { dismiss() }
                }

                ToolbarItem(placement: .primaryAction) {
                    Button {
                        Task { await viewModel.requestTelemetry() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .accessibilityLabel(L10n.RemoteNodes.RemoteNodes.Status.refresh)
                    .radioDisabled(
                        for: appState.connectionState,
                        or: viewModel.helper.isLoadingTelemetry
                    )
                }
            }
            .task {
                viewModel.configure(appState: appState, contact: contact)

                viewModel.helper.telemetryExpanded = true

                if let radioID = appState.connectedDevice?.radioID {
                    await viewModel.helper.loadOCVSettings(
                        publicKey: contact.publicKey,
                        radioID: radioID
                    )
                }
            }
        }
        .presentationDetents([.large])
    }
}
