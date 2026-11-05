import MC1Services
import SwiftUI

struct ContactDetailAvatarView: View {
  private static let avatarSize: CGFloat = 150
  let contact: ContactDTO
  let isSavingAvatar: Bool
  @Binding var showSourceMenu: Bool

  var body: some View {
    if contact.type == .chat {
      Button {
        showSourceMenu = true
      } label: {
        ZStack {
          ContactAvatar(contact: contact, size: Self.avatarSize)
          if isSavingAvatar {
            Circle()
              .fill(.black.opacity(0.4))
              .frame(width: Self.avatarSize, height: Self.avatarSize)
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
        NodeAvatar(publicKey: contact.publicKey, role: .repeater, size: Self.avatarSize)
      case .room:
        NodeAvatar(publicKey: contact.publicKey, role: .roomServer, size: Self.avatarSize)
      case .sensor:
        NodeAvatar(publicKey: contact.publicKey, role: .sensor, size: Self.avatarSize)
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
    .frame(width: Self.avatarSize, height: Self.avatarSize)
  }
}
