import MC1Services
import SwiftUI
import UIKit

struct ContactTechnicalSection: View {
  private static let copyFeedbackDuration = Duration.seconds(2)

  let currentContact: ContactDTO
  let contactTypeLabel: String
  let onRowPosition: (ContactDetailRowPosition.Measurement) -> Void

  @State private var copyHapticTrigger = 0
  @State private var showCopyFeedback = false

  var body: some View {
    Section {
      VStack(alignment: .leading, spacing: 4) {
        Text(L10n.Contacts.Contacts.Detail.publicKey)
          .font(.caption)
          .foregroundStyle(.secondary)
        Text(currentContact.publicKey.uppercaseHexString(separator: " "))
          .font(.system(.caption, design: .monospaced))
          .textSelection(.enabled)
      }
      .modifier(ContactDetailRowPosition(section: .technical, onPosition: onRowPosition))

      Button {
        copyHapticTrigger += 1
        UIPasteboard.general.string = ContactShareContent.compactPublicKeyHex(currentContact.publicKey)
        showCopyFeedback = true
        Task {
          try? await Task.sleep(for: Self.copyFeedbackDuration)
          showCopyFeedback = false
        }
      } label: {
        Label(
          showCopyFeedback
            ? L10n.Contacts.Contacts.Qr.copied
            : L10n.Contacts.Contacts.Qr.copy,
          systemImage: "doc.on.doc"
        )
      }
      .disabled(showCopyFeedback)

      HStack {
        Text(L10n.Contacts.Contacts.Detail.type)
        Spacer()
        Text(contactTypeLabel)
          .foregroundStyle(.secondary)
      }
    } header: {
      Text(L10n.Contacts.Contacts.Detail.technical)
    }
    .sensoryFeedback(.success, trigger: copyHapticTrigger)
  }
}
