import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Contact remote CLI row")
struct ContactDTORemoteCLITests {
  @Test
  func `a chat contact on a v14 radio shows the row`() {
    let contact = makeContact(typeRawValue: ContactType.chat.rawValue)
    #expect(contact.showsRemoteCLI(localRadioSupportsCLI: true))
  }

  @Test
  func `a type 4 sensor stays hidden`() {
    let contact = makeContact(typeRawValue: 4)
    #expect(!contact.showsRemoteCLI(localRadioSupportsCLI: true))
  }

  @Test
  func `a blocked chat contact stays hidden`() {
    let contact = makeContact(typeRawValue: ContactType.chat.rawValue, isBlocked: true)
    #expect(!contact.showsRemoteCLI(localRadioSupportsCLI: true))
  }

  @Test
  func `a v13 radio hides the row`() {
    let contact = makeContact(typeRawValue: ContactType.chat.rawValue)
    #expect(!contact.showsRemoteCLI(localRadioSupportsCLI: false))
  }
}

private func makeContact(typeRawValue: UInt8, isBlocked: Bool = false) -> ContactDTO {
  ContactDTO(
    id: UUID(),
    radioID: UUID(),
    publicKey: Data(repeating: 0x11, count: 32),
    name: "Peer",
    typeRawValue: typeRawValue,
    flags: 0,
    outPathLength: 0xFF,
    outPath: Data(),
    lastAdvertTimestamp: 0,
    latitude: 0,
    longitude: 0,
    lastModified: 0,
    lastHeardTimestamp: nil,
    nickname: nil,
    isBlocked: isBlocked,
    isMuted: false,
    isFavorite: false,
    lastMessageDate: nil,
    unreadCount: 0
  )
}
