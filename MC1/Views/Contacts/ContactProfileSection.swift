import MC1Services
import SwiftUI

struct ContactProfileSection: View {
  let currentContact: ContactDTO
  let contactTypeLabel: String
  @Binding var measuredHeight: CGFloat
  let isSavingAvatar: Bool
  @Binding var showAvatarSourceMenu: Bool
  let onRowPosition: (ContactDetailRowPosition.Measurement) -> Void

  var body: some View {
    Section {
      VStack(spacing: 12) {
        ContactDetailAvatarView(
          contact: currentContact,
          isSavingAvatar: isSavingAvatar,
          showSourceMenu: $showAvatarSourceMenu
        )

        VStack(spacing: 4) {
          Text(currentContact.displayName)
            .font(.title2)
            .bold()

          Text(contactTypeLabel)
            .font(.subheadline)
            .foregroundStyle(.secondary)

          VStack(spacing: 8) {
            if currentContact.isBlocked {
              HStack(spacing: 4) {
                Image(systemName: "hand.raised.fill")
                Text(L10n.Contacts.Contacts.Detail.blocked)
              }
              .font(.caption)
              .foregroundStyle(.red)
            }

            if currentContact.hasLocation {
              HStack(spacing: 4) {
                Image(systemName: "location.fill")
                Text(L10n.Contacts.Contacts.Detail.hasLocation)
              }
              .font(.caption)
              .foregroundStyle(.green)
            }
          }
          .padding(.top, 4)
        }
      }
      .frame(maxWidth: .infinity)
      .scrollRevealHeaderHeight(into: $measuredHeight)
      .listRowBackground(Color.clear)
      .modifier(ContactDetailRowPosition(section: .profile, onPosition: onRowPosition))
    }
  }
}
