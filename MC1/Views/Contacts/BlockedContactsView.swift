import SwiftUI
import MC1Services

/// View showing only blocked contacts for management
struct BlockedContactsView: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

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
                blockedList
            }
        }
        .themedCanvas(theme)
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

    /// Leading inset for the inter-row divider, aligning it under the row text past the avatar.
    private static let rowSeparatorLeadingInset: CGFloat = 72
    private static let rowHorizontalPadding: CGFloat = 16
    private static let rowVerticalPadding: CGFloat = 6

    private var blockedList: some View {
        ScrollView {
            LazyVStack(spacing: 0) {
                ForEach(Array(contacts.enumerated()), id: \.element.id) { index, contact in
                    NavigationLink {
                        ContactDetailView(contact: contact)
                    } label: {
                        ContactRowView(contact: contact)
                            .padding(.horizontal, Self.rowHorizontalPadding)
                            .padding(.vertical, Self.rowVerticalPadding)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .transition(.opacity)
                    if index < contacts.count - 1 {
                        Divider().padding(.leading, Self.rowSeparatorLeadingInset)
                    }
                }
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
