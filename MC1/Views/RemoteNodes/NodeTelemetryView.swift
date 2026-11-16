import MC1Services
import SwiftUI

struct NodeTelemetryView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss

  let contact: ContactDTO
  @State private var viewModel = NodeTelemetryViewModel()

  private var connectionState: DeviceConnectionState {
    appState.connectionSnapshot(for: contact.radioID) == nil ? .disconnected : appState.connectionState
  }

  var body: some View {
    NavigationStack {
      List {
        NodeTelemetryDisclosureSection(helper: viewModel.helper, connectionState: connectionState) {
          await viewModel.requestTelemetry()
        }
      }
      .nodeStatusDestinations(helper: viewModel.helper)
      .themedCanvas(theme)
      .navigationTitle(contact.displayName)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.RemoteNodes.RemoteNodes.done) { dismiss() }
        }

        ToolbarItem(placement: .primaryAction) {
          Button(L10n.RemoteNodes.RemoteNodes.Status.refresh, systemImage: "arrow.clockwise") {
            Task { await viewModel.requestTelemetry() }
          }
          .labelStyle(.iconOnly)
          .radioDisabled(
            for: connectionState,
            or: viewModel.helper.isLoadingTelemetry
          )
        }
      }
      .task {
        let radioID = contact.radioID
        viewModel.configure(
          binaryProtocolService: { appState.connectionSnapshot(for: radioID)?.services.binaryProtocolService },
          contactService: { appState.connectionSnapshot(for: radioID)?.services.contactService },
          nodeSnapshotService: { appState.connectionSnapshot(for: radioID)?.services.nodeSnapshotService },
          contact: contact
        )

        viewModel.helper.telemetryExpanded = true

        if appState.connectionSnapshot(for: radioID) != nil {
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
