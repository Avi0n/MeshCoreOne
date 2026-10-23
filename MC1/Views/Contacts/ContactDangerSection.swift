import MC1Services
import SwiftUI

struct ContactDangerSection: View {
  @Environment(\.appState) private var appState

  let currentContact: ContactDTO
  let contactTypeLabel: String
  let isClearingMessages: Bool
  let isVContact: Bool
  let onClearMessages: () -> Void
  let onToggleBlock: () -> Void
  let onDelete: () -> Void
  let onRowPosition: (ContactDetailRowPosition.Measurement) -> Void

  var body: some View {
    Section {
      if currentContact.type == .chat {
        Button(action: onToggleBlock) {
          Label(
            currentContact.isBlocked ? L10n.Contacts.Contacts.Detail.unblockContact : L10n.Contacts.Contacts.Detail.blockContact,
            systemImage: currentContact.isBlocked ? "hand.raised.slash" : "hand.raised"
          )
        }
        .radioDisabled(for: appState.connectionState)
        .modifier(ContactDetailRowPosition(section: .danger, onPosition: onRowPosition))

        Button(role: .destructive, action: onClearMessages) {
          HStack {
            Label(L10n.Contacts.Contacts.Detail.clearMessages, systemImage: "xmark.circle")
            if isClearingMessages {
              Spacer()
              ProgressView()
            }
          }
        }
        .disabled(isClearingMessages)
      }

      if !isVContact {
        Button(role: .destructive, action: onDelete) {
          Label(L10n.Contacts.Contacts.Detail.deleteType(contactTypeLabel), systemImage: "trash")
        }
        .radioDisabled(for: appState.connectionState)
        .modifier(ContactDetailRowPosition(
          section: .danger, onPosition: currentContact.type == .chat ? nil : onRowPosition
        ))
      }
    } header: {
      Text(L10n.Contacts.Contacts.Detail.dangerZone)
    }
  }
}
