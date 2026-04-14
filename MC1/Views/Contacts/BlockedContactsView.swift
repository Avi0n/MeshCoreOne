import SwiftUI
import MC1Services

/// View showing only blocked contacts for management
struct BlockedContactsView: View {
    @Environment(\.appState) private var appState

    @State private var contacts: [ContactDTO] = []
    @State private var isLoading = false

    var body: some View {
        Group {
            if isLoading {
                ProgressView(L10n.Contacts.Contacts.Blocked.loading)
            } else if contacts.isEmpty {
                ContentUnavailableView(
                    L10n.Contacts.Contacts.Blocked.Empty.title,
                    systemImage: "hand.raised.slash",
                    description: Text(L10n.Contacts.Contacts.Blocked.Empty.description)
                )
            } else {
                List(contacts) { contact in
                    NavigationLink {
                        ContactDetailView(contact: contact)
                    } label: {
                        ContactRowView(contact: contact)
                    }
                }
            }
        }
        .navigationTitle(L10n.Contacts.Contacts.Blocked.title)
        .task {
            await loadBlockedContacts()
        }
        .onChange(of: appState.contactsVersion) { _, _ in
            Task {
                await loadBlockedContacts()
            }
        }
    }

    private func loadBlockedContacts() async {
        guard let services = appState.services,
              let radioID = appState.connectedDevice?.radioID else { return }
        isLoading = true
        defer { isLoading = false }

        do {
            contacts = try await services.dataStore.fetchBlockedContacts(
                radioID: radioID
            )
        } catch {
            contacts = []
        }
    }
}
