import MC1Services
import SwiftUI

/// Shared identity review for a contact QR scan and a `meshcore://contact/add` chat link.
struct ContactAddConfirmationContent: View {
  @Environment(\.appTheme) private var theme

  let contactResult: MeshCoreURLParser.ContactResult
  let existingContact: ContactDTO?
  let errorMessage: String?
  let isAdding: Bool
  let onAdd: () -> Void
  let onScanAgain: (() -> Void)?

  private var displayedName: String {
    existingContact?.displayName ?? contactResult.name
  }

  private var displayedType: ContactType {
    existingContact?.type ?? contactResult.contactType
  }

  private var scannedAsName: String? {
    guard existingContact != nil, contactResult.name != displayedName else { return nil }
    return contactResult.name
  }

  var body: some View {
    Form {
      identitySection
      publicKeySection
      if let errorMessage {
        errorSection(errorMessage)
      }
      addSection
      if let onScanAgain {
        scanAgainSection(onScanAgain)
      }
    }
    .themedCanvas(theme)
  }

  private var identitySection: some View {
    Section {
      VStack(spacing: 12) {
        identityGlyph
          .accessibilityHidden(true)

        Text(displayedName)
          .font(.headline)

        Text(displayedType.localizedName)
          .font(.subheadline)
          .foregroundStyle(.secondary)

        if let scannedAsName {
          Text(L10n.Contacts.Contacts.Add.scannedAs(scannedAsName))
            .font(.footnote)
            .foregroundStyle(.secondary)
        }
      }
      .frame(maxWidth: .infinity)
      .listRowBackground(Color.clear)
      .listRowSeparator(.hidden)
    }
  }

  @ViewBuilder
  private var identityGlyph: some View {
    switch displayedType {
    case .chat:
      ContactAvatar(name: displayedName, size: Constants.identityGlyphSize)
    case .repeater:
      NodeAvatar(
        publicKey: contactResult.publicKey,
        role: .repeater,
        size: Constants.identityGlyphSize
      )
    case .room:
      NodeAvatar(
        publicKey: contactResult.publicKey,
        role: .roomServer,
        size: Constants.identityGlyphSize
      )
    }
  }

  /// Name is claimed; the public key is the verifiable identity.
  private var publicKeySection: some View {
    Section {
      Text(contactResult.publicKey.uppercaseHexString(separator: " "))
        .font(.system(.body, design: .monospaced))
        .textSelection(.enabled)
    } header: {
      Text(L10n.Contacts.Contacts.Add.publicKey)
    }
    .themedRowBackground(theme)
  }

  private func errorSection(_ message: String) -> some View {
    Section {
      Text(message)
        .foregroundStyle(.red)
    }
    .themedRowBackground(theme)
  }

  private var addSection: some View {
    Section {
      Button(action: onAdd) {
        if isAdding {
          ProgressView()
            .frame(maxWidth: .infinity)
        } else {
          Text(primaryButtonTitle)
            .frame(maxWidth: .infinity)
        }
      }
      .liquidGlassProminentButtonStyle()
      .controlSize(.large)
      .disabled(isAdding)
      .accessibilityLabel(primaryAccessibilityLabel)
      .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))
      .listRowBackground(Color.clear)
    }
  }

  private var primaryButtonTitle: String {
    existingContact == nil
      ? L10n.Contacts.Contacts.Add.add
      : L10n.Contacts.Contacts.Add.view
  }

  private var primaryAccessibilityLabel: String {
    if isAdding {
      L10n.Contacts.Contacts.Scan.importing
    } else if let existingContact {
      L10n.Contacts.Contacts.Add.viewAccessibility(existingContact.displayName)
    } else {
      L10n.Contacts.Contacts.Add.add
    }
  }

  private func scanAgainSection(_ action: @escaping () -> Void) -> some View {
    Section {
      Button(action: action) {
        Text(L10n.Contacts.Contacts.Scan.scanAgain)
          .frame(maxWidth: .infinity)
      }
      .disabled(isAdding)
    }
    .themedRowBackground(theme)
  }
}

private enum Constants {
  static let identityGlyphSize: CGFloat = 56
}

#Preview("Repeater") {
  NavigationStack {
    ContactAddConfirmationContent(
      contactResult: MeshCoreURLParser.ContactResult(
        name: "Example Repeater",
        publicKey: Data(repeating: 0xAA, count: 32),
        contactType: .repeater
      ),
      existingContact: nil,
      errorMessage: nil,
      isAdding: false,
      onAdd: {},
      onScanAgain: {}
    )
    .navigationTitle(L10n.Contacts.Contacts.Add.nodeTitle)
    .navigationBarTitleDisplayMode(.inline)
  }
}

#Preview("Already added") {
  NavigationStack {
    ContactAddConfirmationContent(
      contactResult: MeshCoreURLParser.ContactResult(
        name: "Claimed Name",
        publicKey: Data(repeating: 0xAA, count: 32),
        contactType: .repeater
      ),
      existingContact: ContactDTO(
        id: UUID(),
        radioID: UUID(),
        publicKey: Data(repeating: 0xAA, count: 32),
        name: "Saved Repeater",
        typeRawValue: ContactType.repeater.rawValue,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        lastAdvertTimestamp: 0,
        latitude: 0,
        longitude: 0,
        lastModified: 0,
        lastHeardTimestamp: nil,
        nickname: nil,
        isBlocked: false,
        isMuted: false,
        isFavorite: false,
        lastMessageDate: nil,
        unreadCount: 0
      ),
      errorMessage: nil,
      isAdding: false,
      onAdd: {},
      onScanAgain: {}
    )
    .navigationTitle("Saved Repeater")
    .navigationBarTitleDisplayMode(.inline)
  }
}
