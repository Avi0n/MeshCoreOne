import MC1Services
import SwiftUI

/// Sheet for sharing a contact via QR code
struct ContactQRShareSheet: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.dismiss) private var dismiss

  let contactName: String
  let publicKey: Data
  let contactType: ContactType

  @State private var showCopyFeedback = false
  @State private var qrImage: UIImage?
  @State private var copyHapticTrigger = 0

  private var contactURI: String {
    ContactService.exportContactURI(name: contactName, publicKey: publicKey, type: contactType)
  }

  var body: some View {
    NavigationStack {
      Form {
        // QR Code Section
        QRCodeSection(contactName: contactName, qrImage: qrImage)
          .themedRowBackground(theme)

        // Contact Info Section
        ContactInfoSection(publicKey: publicKey)
          .themedRowBackground(theme)

        // Actions Section
        ActionsSection(
          qrImage: qrImage,
          shareText: shareText,
          contactName: contactName,
          showCopyFeedback: $showCopyFeedback,
          copyToClipboard: copyToClipboard
        )
        .themedRowBackground(theme)
      }
      .themedCanvas(theme)
      .navigationTitle(L10n.Contacts.Contacts.Qr.title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button(L10n.Contacts.Contacts.Common.done) {
            dismiss()
          }
        }
      }
      .onAppear {
        qrImage = generateQRCode()
      }
      .sensoryFeedback(.success, trigger: copyHapticTrigger)
    }
  }

  // MARK: - Private Methods

  private func generateQRCode() -> UIImage? {
    QRCodeGenerator.generate(
      from: contactURI, scale: Constants.qrScale, correctionLevel: Constants.qrCorrectionLevel
    )
  }

  private var shareText: String {
    """
    \(L10n.Contacts.Contacts.Share.contactLabel(contactName))
    \(L10n.Contacts.Contacts.Share.keyLabel(publicKey.hexString))
    \(contactURI)
    """
  }

  private func copyToClipboard() {
    copyHapticTrigger += 1
    UIPasteboard.general.string = publicKey.hexString
    showCopyFeedback = true

    Task {
      try? await Task.sleep(for: Constants.copyFeedbackDuration)
      showCopyFeedback = false
    }
  }
}

// MARK: - Constants

private enum Constants {
  static let qrScale = 10.0
  static let qrCorrectionLevel = "M"
  static let copyFeedbackDuration = Duration.seconds(2)
  static let qrCodeSize: CGFloat = 200
}

// MARK: - QR Code Section

private struct QRCodeSection: View {
  let contactName: String
  let qrImage: UIImage?

  var body: some View {
    Section {
      HStack {
        Spacer()
        VStack(spacing: 12) {
          if let qrImage {
            Image(uiImage: qrImage)
              .interpolation(.none)
              .resizable()
              .scaledToFit()
              .foregroundStyle(.primary)
              .frame(width: Constants.qrCodeSize, height: Constants.qrCodeSize)
          }

          Text(contactName)
            .font(.title2)
            .bold()
        }
        Spacer()
      }
    }
  }
}

// MARK: - Contact Info Section

private struct ContactInfoSection: View {
  let publicKey: Data

  var body: some View {
    Section {
      VStack(alignment: .leading, spacing: 8) {
        Text(L10n.Contacts.Contacts.Add.publicKey)
          .font(.caption)
          .foregroundStyle(.secondary)

        Text(publicKey.uppercaseHexString(separator: " "))
          .font(.system(.body, design: .monospaced))
          .textSelection(.enabled)
      }
    }
  }
}

// MARK: - Actions Section

private struct ActionsSection: View {
  let qrImage: UIImage?
  let shareText: String
  let contactName: String
  @Binding var showCopyFeedback: Bool
  let copyToClipboard: () -> Void

  var body: some View {
    Section {
      Button {
        copyToClipboard()
      } label: {
        HStack {
          Spacer()
          Label(
            showCopyFeedback
              ? L10n.Contacts.Contacts.Qr.copied
              : L10n.Contacts.Contacts.Qr.copy,
            systemImage: "doc.on.doc"
          )
          Spacer()
        }
      }
      .disabled(showCopyFeedback)
      .alignmentGuide(.listRowSeparatorLeading) { dimensions in dimensions[.leading] }

      if let qrImage {
        ShareLink(
          item: shareText,
          subject: Text(L10n.Contacts.Contacts.Qr.shareSubject),
          preview: SharePreview(contactName, image: Image(uiImage: qrImage))
        ) {
          HStack {
            Spacer()
            Label(L10n.Contacts.Contacts.Qr.share, systemImage: "square.and.arrow.up")
            Spacer()
          }
        }
      }
    }
  }
}
