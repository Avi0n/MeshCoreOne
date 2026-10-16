import CoreLocation
import MC1Services
import OSLog
import SwiftUI

private let logger = Logger(subsystem: "com.mc1", category: "BlockSenderSheet")

/// Confirmation sheet for blocking a channel sender name.
/// Shows name-based limitation warning and any matching contacts the user can optionally block.
struct BlockSenderSheet: View {
  @Environment(\.appState) private var appState
  @Environment(\.dismiss) private var dismiss

  let senderName: String
  let radioID: UUID
  let onBlock: (_ blockedContactIDs: Set<UUID>) -> Void

  @State private var matchingContacts: [ContactDTO] = []
  @State private var selectedContactIDs: Set<UUID> = []

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 16) {
          Text(L10n.Chats.Chats.BlockSender.limitation)
            .font(.subheadline)
            .foregroundStyle(.secondary)

          if !matchingContacts.isEmpty {
            ContactMatchSection(
              contacts: matchingContacts,
              selectedIDs: $selectedContactIDs,
              userLocation: appState.bestAvailableLocation
            )
          }
        }
        .padding()
      }
      .navigationTitle(L10n.Chats.Chats.BlockSender.title(senderName))
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button(L10n.Chats.Chats.BlockSender.cancel) {
            dismiss()
          }
        }
        ToolbarItem(placement: .destructiveAction) {
          Button(L10n.Chats.Chats.BlockSender.blockAnyway, role: .destructive) {
            onBlock(selectedContactIDs)
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

// MARK: - Contact Match Section

private struct ContactMatchSection: View {
  let contacts: [ContactDTO]
  @Binding var selectedIDs: Set<UUID>
  let userLocation: CLLocation?

  var body: some View {
    VStack(alignment: .leading, spacing: 12) {
      Text(L10n.Chats.Chats.BlockSender.matchingContacts)
        .font(.subheadline)
        .foregroundStyle(.secondary)

      ForEach(contacts) { contact in
        ContactMatchRow(
          contact: contact,
          style: .toggle(isSelected: selectedIDs.contains(contact.id)),
          userLocation: userLocation,
          action: {
            if selectedIDs.contains(contact.id) {
              selectedIDs.remove(contact.id)
            } else {
              selectedIDs.insert(contact.id)
            }
          }
        )
      }
    }
  }
}
