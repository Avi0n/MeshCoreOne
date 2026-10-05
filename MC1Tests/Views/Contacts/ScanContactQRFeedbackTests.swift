import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Scan contact QR feedback")
struct ScanContactQRFeedbackTests {
  fileprivate static let samplePublicKey = Data(repeating: 0xAA, count: 32)

  @Test
  @MainActor
  func `idle add button uses add and hides the type glyph`() {
    let content = makeContent(isAdding: false)
    let hex = Self.samplePublicKey.uppercaseHexString(separator: " ")
    #expect(content.primaryAccessibilityLabel == L10n.Contacts.Contacts.Add.add)
    #expect(content.primaryAccessibilityLabel != L10n.Contacts.Contacts.Scan.importing)
    #expect(content.primaryAccessibilityLabel != L10n.Contacts.Contacts.Add.alreadyAdded)
    #expect(content.hidesIdentityGlyph)
    #expect(content.publicKeyText.contains(hex))
    #expect(!content.showsScanAgain)
  }

  @Test
  @MainActor
  func `importing add button uses importing not add`() {
    let content = makeContent(isAdding: true)
    #expect(content.primaryAccessibilityLabel == L10n.Contacts.Contacts.Scan.importing)
    #expect(content.primaryAccessibilityLabel != L10n.Contacts.Contacts.Add.add)
  }

  @Test
  @MainActor
  func `scan again appears when provided`() {
    let content = makeContent(isAdding: false, onScanAgain: {})
    #expect(content.showsScanAgain)
  }

  @Test
  @MainActor
  func `existing contact uses view not add`() {
    let existing = sampleContact(name: "Example Repeater")
    let content = makeContent(existingContact: existing)
    #expect(content.primaryAccessibilityLabel == L10n.Contacts.Contacts.Add.viewAccessibility(existing.displayName))
    #expect(content.primaryAccessibilityLabel != L10n.Contacts.Contacts.Add.alreadyAdded)
    #expect(content.primaryAccessibilityLabel != L10n.Contacts.Contacts.Add.add)
    #expect(content.scannedAsText == nil)
  }

  @Test
  @MainActor
  func `name mismatch shows scanned as caption`() {
    let existing = sampleContact(name: "Saved Repeater")
    let content = makeContent(qrName: "Claimed Name", existingContact: existing)
    #expect(content.displayedName == existing.displayName)
    #expect(content.scannedAsText == L10n.Contacts.Contacts.Add.scannedAs("Claimed Name"))
  }

  @MainActor
  private func makeContent(
    isAdding: Bool = false,
    qrName: String = "Example Repeater",
    existingContact: ContactDTO? = nil,
    onScanAgain: (() -> Void)? = nil
  ) -> ContactAddConfirmationContent {
    ContactAddConfirmationContent(
      contactResult: MeshCoreURLParser.ContactResult(
        name: qrName,
        publicKey: Self.samplePublicKey,
        contactType: .repeater
      ),
      existingContact: existingContact,
      errorMessage: nil,
      isAdding: isAdding,
      onAdd: {},
      onScanAgain: onScanAgain
    )
  }
}

private func sampleContact(name: String) -> ContactDTO {
  ContactDTO(
    id: UUID(),
    radioID: UUID(),
    publicKey: ScanContactQRFeedbackTests.samplePublicKey,
    name: name,
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
  )
}
