@testable import MC1
import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Scan contact QR feedback")
struct ScanContactQRFeedbackTests {
  fileprivate static let samplePublicKey = Data(repeating: 0xAA, count: 32)

  @Test
  @MainActor
  func `idle add button uses add and hides the type glyph`() {
    let labels = hostedLabels(isAdding: false)
    let hex = Self.samplePublicKey.uppercaseHexString(separator: " ")
    #expect(labels.contains(L10n.Contacts.Contacts.Add.add))
    #expect(!labels.contains(L10n.Contacts.Contacts.Scan.importing))
    #expect(!labels.contains(L10n.Contacts.Contacts.Add.alreadyAdded))
    #expect(!labels.contains { $0.localizedCaseInsensitiveContains("antenna") })
    #expect(labels.contains { $0.contains(hex) })
    #expect(!labels.contains(L10n.Contacts.Contacts.Scan.scanAgain))
  }

  @Test
  @MainActor
  func `importing add button uses importing not add`() {
    let labels = hostedLabels(isAdding: true)
    #expect(labels.contains(L10n.Contacts.Contacts.Scan.importing))
    #expect(!labels.contains(L10n.Contacts.Contacts.Add.add))
  }

  @Test
  @MainActor
  func `scan again appears when provided`() {
    let labels = hostedLabels(isAdding: false, onScanAgain: {})
    #expect(labels.contains(L10n.Contacts.Contacts.Scan.scanAgain))
  }

  @Test
  @MainActor
  func `existing contact uses view not add`() {
    let existing = sampleContact(name: "Example Repeater")
    let labels = hostedLabels(existingContact: existing)
    #expect(labels.contains(L10n.Contacts.Contacts.Add.viewAccessibility(existing.displayName)))
    #expect(!labels.contains(L10n.Contacts.Contacts.Add.alreadyAdded))
    #expect(!labels.contains(L10n.Contacts.Contacts.Add.add))
    #expect(!labels.contains(L10n.Contacts.Contacts.Add.scannedAs("Example Repeater")))
  }

  @Test
  @MainActor
  func `name mismatch shows scanned as caption`() {
    let existing = sampleContact(name: "Saved Repeater")
    let labels = hostedLabels(
      qrName: "Claimed Name",
      existingContact: existing
    )
    #expect(labels.contains(existing.displayName))
    #expect(labels.contains(L10n.Contacts.Contacts.Add.scannedAs("Claimed Name")))
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

@MainActor
private func hostedLabels(
  isAdding: Bool = false,
  qrName: String = "Example Repeater",
  existingContact: ContactDTO? = nil,
  onScanAgain: (() -> Void)? = nil
) -> [String] {
  let content = ContactAddConfirmationContent(
    contactResult: MeshCoreURLParser.ContactResult(
      name: qrName,
      publicKey: ScanContactQRFeedbackTests.samplePublicKey,
      contactType: .repeater
    ),
    existingContact: existingContact,
    errorMessage: nil,
    isAdding: isAdding,
    onAdd: {},
    onScanAgain: onScanAgain
  )
  .frame(width: 390, height: 800)

  let host = UIHostingController(rootView: content)
  let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 800))
  window.rootViewController = host
  window.isHidden = false
  window.layoutIfNeeded()
  return accessibilityLabels(in: host.view)
}

@MainActor
private func accessibilityLabels(in view: UIView) -> [String] {
  var labels: [String] = []
  func appendLabel(_ object: NSObject) {
    if let label = object.accessibilityLabel, !label.isEmpty {
      labels.append(label)
    }
    if let text = (object as? UILabel)?.text, !text.isEmpty {
      labels.append(text)
    }
  }
  if view.accessibilityElementsHidden { return labels }
  if view.isAccessibilityElement {
    appendLabel(view)
  }
  if let elements = view.accessibilityElements {
    for element in elements {
      guard let object = element as? NSObject else { continue }
      appendLabel(object)
    }
  }
  let count = view.accessibilityElementCount()
  if count != NSNotFound {
    for index in 0..<count {
      if let object = view.accessibilityElement(at: index) as? NSObject {
        appendLabel(object)
      }
    }
  }
  for subview in view.subviews {
    labels.append(contentsOf: accessibilityLabels(in: subview))
  }
  return labels
}
