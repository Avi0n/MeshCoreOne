import MC1Services
import SwiftUI

struct NewChatView: View {
  @Environment(\.dismiss) private var dismiss
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  let onSelectContact: (ContactDTO) -> Void

  @State private var contacts: [ContactDTO] = []
  @State private var searchText = ""
  @State private var isLoading = false

  private var filteredContacts: [ContactDTO] {
    let eligible = contacts.filter { !$0.isBlocked && $0.type != .repeater && $0.type != .room }
    guard !searchText.isEmpty else { return eligible }
    return eligible.filter { contact in
      contact.displayName.localizedStandardContains(searchText)
    }
  }

  var body: some View {
    NavigationStack {
      Group {
        if isLoading {
          ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if contacts.isEmpty {
          ContentUnavailableView(
            L10n.Chats.Chats.NewChat.EmptyState.title,
            systemImage: "person.2",
            description: Text(L10n.Chats.Chats.NewChat.EmptyState.description)
          )
        } else {
          List(filteredContacts) { contact in
            Button {
              onSelectContact(contact)
            } label: {
              HStack(spacing: 12) {
                ContactAvatar(contact: contact, size: 40)

                VStack(alignment: .leading) {
                  Text(contact.displayName)
                    .font(.headline)

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
          .listStyle(.insetGrouped)
          .contentMargins(.top, 0, for: .scrollContent)
          .themedCanvas(theme)
        }
      }
      .navigationTitle(L10n.Chats.Chats.NewChat.title)
      .navigationBarTitleDisplayMode(.inline)
      .searchable(text: $searchText, prompt: L10n.Chats.Chats.NewChat.Search.placeholder)
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
    }
  }

  private func loadContacts() async {
    guard let radioID = appState.connectedDevice?.radioID else { return }

    isLoading = true
    contacts = await (try? appState.services?.dataStore.fetchContacts(radioID: radioID)) ?? []
    isLoading = false
  }

  private func contactTypeLabel(for contact: ContactDTO) -> String {
    switch contact.type {
    case .chat:
      contact.isFloodRouted ? L10n.Chats.Chats.ConnectionStatus.floodRouting : L10n.Chats.Chats.NewChat.ContactType.direct
    case .repeater:
      L10n.Chats.Chats.NewChat.ContactType.repeater
    case .room:
      ""
    }
  }
}
