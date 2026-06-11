import OSLog
import MC1Services
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "ShareContactPickerSheet")

private enum Layout {
    static let avatarSize: CGFloat = 40
    static let rowSpacing: CGFloat = 12
    static let rowVerticalSpacing: CGFloat = 2
}

/// Sheet for picking a contact to share as a MeshCore contact-share token.
/// Calls `onInsert` with the formatted token and dismisses on selection.
struct ShareContactPickerSheet: View {
    let onInsert: (String) -> Void

    @Environment(\.appState) private var appState
    @Environment(\.dismiss) private var dismiss
    @Environment(\.appTheme) private var theme

    @State private var contacts: [ContactDTO] = []
    @State private var searchText = ""
    @State private var isLoading = true
    @State private var errorMessage: String?

    private var filteredContacts: [ContactDTO] {
        guard !searchText.isEmpty else { return contacts }
        return contacts.filter { contact in
            contact.name.localizedCaseInsensitiveContains(searchText) ||
            (contact.nickname?.localizedCaseInsensitiveContains(searchText) ?? false) ||
            contact.publicKey.hexString().hasPrefix(searchText.uppercased())
        }
    }

    var body: some View {
        NavigationStack {
            Group {
                if isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else if filteredContacts.isEmpty {
                    ContentUnavailableView(
                        L10n.Chats.Chats.ContactPicker.emptyState,
                        systemImage: "person.2"
                    )
                } else {
                    List(filteredContacts) { contact in
                        Button {
                            let token = ContactShareUtilities.formatShare(
                                publicKey: contact.publicKey,
                                type: contact.type,
                                name: contact.name
                            )
                            onInsert(token)
                            dismiss()
                        } label: {
                            HStack(spacing: Layout.rowSpacing) {
                                ContactAvatar(contact: contact, size: Layout.avatarSize)

                                VStack(alignment: .leading, spacing: Layout.rowVerticalSpacing) {
                                    (Text(idPrefixHex(for: contact))
                                        .monospaced()
                                        .foregroundStyle(.secondary)
                                        + Text(" \(contact.displayName)"))
                                        .font(.headline)
                                        .accessibilityLabel(contact.displayName)

                                    Text(contactTypeLabel(for: contact))
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }

                                Spacer()
                            }
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                    }
                    .themedCanvas(theme)
                }
            }
            .navigationTitle(L10n.Chats.Chats.ContactPicker.title)
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchText, prompt: L10n.Chats.Chats.ContactPicker.Search.placeholder)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L10n.Chats.Chats.Common.cancel) {
                        dismiss()
                    }
                }
            }
            .task {
                await loadContacts()
            }
            .errorAlert($errorMessage)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
        .presentationBackground(.background)
    }

    private func loadContacts() async {
        defer { isLoading = false }

        guard let radioID = appState.currentRadioID,
              let store = appState.offlineDataStore else {
            logger.warning("No radio or data store available for contact picker")
            return
        }

        do {
            contacts = try await store.fetchContacts(radioID: radioID)
                .filter { !$0.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
            logger.info("Loaded \(contacts.count) contacts for share picker")
        } catch {
            logger.error("Failed to fetch contacts for share picker: \(error)")
            errorMessage = error.localizedDescription
        }
    }

    private func idPrefixHex(for contact: ContactDTO) -> String {
        let hashSize = appState.connectedDevice?.hashSize ?? 1
        return contact.publicKey.prefix(hashSize).hexString()
    }

    private func contactTypeLabel(for contact: ContactDTO) -> String {
        contact.type.localizedName
    }
}

#Preview("Empty state") {
    ShareContactPickerSheet(onInsert: { _ in })
}
