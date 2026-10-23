import MC1Services
import SwiftUI

struct ContactDetailAvatarView: View {
  let contact: ContactDTO
  let isSavingAvatar: Bool
  @Binding var showSourceMenu: Bool

  var body: some View {
    if contact.type == .chat {
      Button {
        showSourceMenu = true
      } label: {
        ZStack {
          ContactAvatar(contact: contact, size: 150)
          if isSavingAvatar {
            Circle()
              .fill(.black.opacity(0.4))
              .frame(width: 150, height: 150)
            ProgressView()
              .tint(.white)
          }
          editBadge
        }
      }
      .buttonStyle(.plain)
      .disabled(isSavingAvatar)
      .accessibilityLabel(accessibilityLabel)
    } else {
      switch contact.type {
      case .repeater:
        NodeAvatar(publicKey: contact.publicKey, role: .repeater, size: 150)
      case .room:
        NodeAvatar(publicKey: contact.publicKey, role: .roomServer, size: 150)
      case .chat:
        EmptyView()
      }
    }
  }

  private var accessibilityLabel: String {
    guard isSavingAvatar else {
      return "\(contact.displayName), \(L10n.Contacts.Contacts.Detail.Avatar.chooseSource)"
    }
    return "\(contact.displayName), \(L10n.Contacts.Contacts.Detail.Avatar.savingAnnouncement)"
  }

  private var editBadge: some View {
    VStack {
      Spacer()
      HStack {
        Spacer()
        Image(systemName: "camera.circle.fill")
          .font(.system(size: 32))
          .symbolRenderingMode(.palette)
          .foregroundStyle(.white, .gray)
      }
    }
    .frame(width: 150, height: 150)
  }
}
