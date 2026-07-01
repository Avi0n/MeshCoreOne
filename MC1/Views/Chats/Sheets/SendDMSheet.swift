import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "SendDMSheet")

/// Picker sheet for starting a DM with a channel sender.
/// Resolves the sender's display name to matching Contacts (case-insensitive) and lets the user pick one.
struct SendDMSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  let senderName: String
  let radioID: UUID
  let onSelect: (ContactDTO) -> Void

  @State private var matchingContacts: [ContactDTO] = []
  @State private var isLoading = true

  var body: some View {
    NavigationStack {
      Group {
        if isLoading {
          ProgressView()
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if matchingContacts.isEmpty {
          ContentUnavailableView(
            L10n.Chats.Chats.SendDM.noMatches(senderName),
            systemImage: "person.crop.circle.badge.questionmark"
          )
        } else {
          ScrollView {
            VStack(alignment: .leading, spacing: 12) {
              Text(L10n.Chats.Chats.SendDM.limitation)
                .font(.subheadline)
                .foregroundStyle(.secondary)

              Text(L10n.Chats.Chats.SendDM.matchingContacts)
                .font(.subheadline)
                .foregroundStyle(.secondary)

              ForEach(matchingContacts) { contact in
                ContactMatchRow(
                  contact: contact,
                  style: .tap,
                  userLocation: appState.bestAvailableLocation,
                  action: {
                    onSelect(contact)
                    dismiss()
                  }
                )
              }
            }
            .padding()
          }
        }
      }
      .navigationTitle(L10n.Chats.Chats.SendDM.title(senderName))
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Chats.Chats.SendDM.cancel) {
            dismiss()
          }
        }
      }
      .task {
        await loadMatchingContacts()
      }
    }
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
    .presentationBackground(.background)
  }

  private func loadMatchingContacts() async {
    defer { isLoading = false }

    guard let store = appState.offlineDataStore else {
      logger.warning("No data store available for contact matching")
      return
    }

    do {
      let allContacts = try await store.fetchContacts(radioID: radioID)
      matchingContacts = SenderContactMatcher.filter(
        contacts: allContacts,
        senderName: senderName,
        excludeBlocked: true
      )
      logger.info("Found \(matchingContacts.count) matching contacts for sender '\(senderName)'")
    } catch {
      logger.error("Failed to fetch contacts for matching: \(error)")
    }
  }
}
