import MC1Services
import SwiftUI

/// Guest standalone sheet for repeater stats, telemetry, and neighbors.
struct RepeaterStatusView: View {
    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss

    let session: RemoteNodeSessionDTO
    @State private var viewModel = RepeaterStatusViewModel()
    @State private var contacts: [ContactDTO] = []
    @State private var discoveredNodes: [DiscoveredNodeDTO] = []
    /// The node's contact, kept live so the route section reflects the path the firmware learns after
    /// a flood login (delivered asynchronously as a contact update).
    @State private var routeContact: ContactDTO?

    var body: some View {
        NavigationStack {
            RepeaterStatusContent(
                viewModel: viewModel,
                session: session,
                connectionState: appState.connectionState,
                contacts: contacts,
                discoveredNodes: discoveredNodes,
                userLocation: appState.bestAvailableLocation,
                connectedDeviceID: appState.connectedDevice?.radioID,
                routePathContact: routeContact
            )
            .navigationTitle(L10n.RemoteNodes.RemoteNodes.Status.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L10n.RemoteNodes.RemoteNodes.done) { dismiss() }
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
                viewModel.configure(
                    repeaterAdminService: { appState.services?.repeaterAdminService },
                    contactService: { appState.services?.contactService },
                    nodeSnapshotService: { appState.services?.nodeSnapshotService }
                )
                await viewModel.registerHandlers()

                // Pre-load OCV settings and contacts for neighbor matching
                if let radioID = appState.connectedDevice?.radioID {
                    await viewModel.helper.loadOCVSettings(publicKey: session.publicKey, radioID: radioID)
                    if let dataStore = appState.services?.dataStore {
                        contacts = (try? await dataStore.fetchContacts(radioID: radioID)) ?? []
                        discoveredNodes = (try? await dataStore.fetchDiscoveredNodes(radioID: radioID)) ?? []
                    }
                }
                await refreshRouteContact()
            }
            .onChange(of: appState.contactsVersion) {
                Task { await refreshRouteContact() }
            }
        }
        .onDisappear {
            viewModel.stopDiscovery()
            Task { await viewModel.cleanup() }
        }
        .presentationDetents([.large])
    }

    private func refreshRouteContact() async {
        guard let dataStore = appState.services?.dataStore else { return }
        if let updated = (try? await dataStore.fetchContact(
            radioID: session.radioID,
            publicKey: session.publicKey
        )).flatMap({ $0 }) {
            routeContact = updated
        }
    }
}

#Preview {
    RepeaterStatusView(
        session: RemoteNodeSessionDTO(
            radioID: UUID(),
            publicKey: Data(repeating: 0x42, count: 32),
            name: "Test Repeater",
            role: .repeater,
            isConnected: true,
            permissionLevel: .admin
        )
    )
    .environment(\.appState, AppState())
}
