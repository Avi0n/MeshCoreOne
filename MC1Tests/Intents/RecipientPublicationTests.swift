import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
struct RecipientPublicationTests {
  private static let radioA = UUID(uuidString: "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")!
  private static let radioB = UUID(uuidString: "BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")!

  private func makeContact(radioID: UUID, publicKey: Data, name: String, type: ContactType = .chat) -> ContactDTO {
    ContactDTO(
      id: UUID(), radioID: radioID, publicKey: publicKey, name: name,
      typeRawValue: type.rawValue, flags: 0, outPathLength: 0, outPath: Data(),
      lastAdvertTimestamp: 0, latitude: 0, longitude: 0, lastModified: 0,
      lastHeardTimestamp: nil,
      nickname: nil, isBlocked: false, isMuted: false, isFavorite: false,
      lastMessageDate: nil, unreadCount: 0
    )
  }

  private func makeChannel(radioID: UUID, index: UInt8, name: String, secret: Data) -> ChannelDTO {
    ChannelDTO(
      id: UUID(), radioID: radioID, index: index, name: name, secret: secret,
      isEnabled: true, lastMessageDate: nil, unreadCount: 0
    )
  }

  private func channelID(radioID: UUID, secret: Data) -> String {
    formatCompositeID(
      radioID: radioID,
      kind: .channel,
      keyHex: channelSecretDigestHex(radioID: radioID, secret: secret)
    )
  }

  private func contactID(radioID: UUID, publicKey: Data) -> String {
    formatCompositeID(radioID: radioID, kind: .contact, keyHex: publicKey.hexString)
  }

  private func decide(
    previous: Set<String>?,
    captured: Set<String>,
    fresh: Set<String>? = nil,
    capturedRadioID: UUID? = RecipientPublicationTests.radioA,
    currentRadioID: UUID? = RecipientPublicationTests.radioA
  ) -> Bool {
    shouldPublishRecipientIDs(
      previous: previous,
      capturedRadioID: capturedRadioID,
      captured: captured,
      currentRadioID: currentRadioID,
      fresh: fresh ?? captured
    )
  }

  @Test func `offered ids match the picker list`() {
    let personKey = Data(repeating: 0x01, count: 32)
    let repeaterKey = Data(repeating: 0x02, count: 32)
    let sharedSecret = Data(repeating: 0x0B, count: 16)
    let uniqueSecret = Data(repeating: 0x0C, count: 16)
    let contacts = [
      makeContact(radioID: Self.radioA, publicKey: personKey, name: "Person", type: .chat),
      makeContact(radioID: Self.radioA, publicKey: repeaterKey, name: "Repeater", type: .repeater)
    ]
    let channels = [
      makeChannel(radioID: Self.radioA, index: 0, name: "Ops", secret: uniqueSecret),
      makeChannel(radioID: Self.radioA, index: 1, name: "DupA", secret: sharedSecret),
      makeChannel(radioID: Self.radioA, index: 2, name: "DupB", secret: sharedSecret)
    ]

    let offered = offeredRecipientIDs(contacts: contacts, channels: channels)
    let picker = Set(buildMessageTargets(contacts: chatContacts(contacts), channels: channels).map(\.id))

    #expect(offered == picker)
    #expect(offered.contains(contactID(radioID: Self.radioA, publicKey: personKey)))
    #expect(!offered.contains(contactID(radioID: Self.radioA, publicKey: repeaterKey)))
    #expect(offered.contains(channelID(radioID: Self.radioA, secret: uniqueSecret)))
    #expect(!offered.contains(channelID(radioID: Self.radioA, secret: sharedSecret)))
  }

  @Test func `equal sets do not publish, including empty`() {
    #expect(!decide(previous: [], captured: []))
    let same: Set = ["a"]
    #expect(!decide(previous: same, captured: same))
  }

  @Test func `nil previous publishes an empty set and a non-empty set`() {
    #expect(decide(previous: nil, captured: []))
    #expect(decide(previous: nil, captured: ["a"]))
  }

  @Test func `secret and public-key changes publish`() {
    let secretA = Data(repeating: 0x11, count: 16)
    let secretB = Data(repeating: 0x22, count: 16)
    let keyA = Data(repeating: 0xA1, count: 32)
    let keyB = Data(repeating: 0xB2, count: 32)
    let channelA = makeChannel(radioID: Self.radioA, index: 0, name: "A", secret: secretA)
    let channelB = makeChannel(radioID: Self.radioA, index: 1, name: "B", secret: secretB)
    let contactA = makeContact(radioID: Self.radioA, publicKey: keyA, name: "Ada")
    let contactB = makeContact(radioID: Self.radioA, publicKey: keyB, name: "Bea")

    let onlyA = offeredRecipientIDs(contacts: [], channels: [channelA])
    let bothChannels = offeredRecipientIDs(contacts: [], channels: [channelA, channelB])
    let onlyB = offeredRecipientIDs(contacts: [], channels: [channelB])
    let onlyContactA = offeredRecipientIDs(contacts: [contactA], channels: [])
    let bothContacts = offeredRecipientIDs(contacts: [contactA, contactB], channels: [])

    #expect(decide(previous: onlyA, captured: bothChannels))
    #expect(decide(previous: onlyA, captured: onlyB))
    #expect(decide(previous: onlyContactA, captured: bothContacts))
  }

  @Test func `a chat contact keeps its id and a repeater that becomes chat publishes`() {
    let key = Data(repeating: 0xC3, count: 32)
    let renamed = makeContact(radioID: Self.radioA, publicKey: key, name: "Renamed", type: .chat)
    let original = makeContact(radioID: Self.radioA, publicKey: key, name: "Original", type: .chat)
    let repeater = makeContact(radioID: Self.radioA, publicKey: key, name: "Tower", type: .repeater)
    let chat = makeContact(radioID: Self.radioA, publicKey: key, name: "Tower", type: .chat)

    let chatIDs = offeredRecipientIDs(contacts: [original], channels: [])
    let renamedIDs = offeredRecipientIDs(contacts: [renamed], channels: [])
    let repeaterIDs = offeredRecipientIDs(contacts: [repeater], channels: [])
    let promotedIDs = offeredRecipientIDs(contacts: [chat], channels: [])

    #expect(chatIDs == renamedIDs)
    #expect(!decide(previous: chatIDs, captured: renamedIDs))
    #expect(repeaterIDs.isEmpty)
    #expect(decide(previous: repeaterIDs, captured: promotedIDs))
  }

  @Test func `a stale fresh read or a different radio does not publish`() {
    let captured: Set = ["a"]
    #expect(!decide(previous: nil, captured: captured, fresh: ["b"]))
    #expect(!decide(previous: nil, captured: captured, currentRadioID: Self.radioB))
    #expect(!decide(previous: nil, captured: captured, currentRadioID: nil))
    #expect(!decide(previous: nil, captured: captured, capturedRadioID: nil, currentRadioID: Self.radioA))
  }
}
