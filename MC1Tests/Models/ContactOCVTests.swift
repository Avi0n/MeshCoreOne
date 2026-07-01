import Foundation
@testable import MC1Services
import Testing

@Suite("Contact OCV Tests")
struct ContactOCVTests {
  @Test
  func `activeOCVArray returns Li-Ion by default`() {
    let contact = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0xFF,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )

    #expect(contact.activeOCVArray == OCVPreset.liIon.ocvArray)
  }

  @Test
  func `activeOCVArray returns preset array when set`() {
    let contact = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0xFF,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: OCVPreset.liFePO4.rawValue,
      customOCVArrayString: nil
    )

    #expect(contact.activeOCVArray == OCVPreset.liFePO4.ocvArray)
  }

  @Test
  func `activeOCVArray returns custom array when valid`() {
    let customArray = [4200, 4100, 4000, 3900, 3800, 3700, 3600, 3500, 3400, 3300, 3200]
    let customString = customArray.map(String.init).joined(separator: ",")

    let contact = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0xFF,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: OCVPreset.custom.rawValue,
      customOCVArrayString: customString
    )

    #expect(contact.activeOCVArray == customArray)
  }

  @Test
  func `activeOCVArray falls back to Li-Ion for invalid custom array`() {
    let contact = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0xFF,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      ocvPreset: OCVPreset.custom.rawValue,
      customOCVArrayString: "invalid"
    )

    #expect(contact.activeOCVArray == OCVPreset.liIon.ocvArray)
  }
}
