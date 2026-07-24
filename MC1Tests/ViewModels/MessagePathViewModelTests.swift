import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MessagePathViewModel")
@MainActor
struct MessagePathViewModelTests {
  private func createContact(
    prefix: [UInt8],
    name: String,
    type: ContactType = .chat,
    lastAdvertTimestamp: UInt32 = 0
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(prefix + Array(repeating: UInt8(0), count: 32 - prefix.count)),
      name: name,
      typeRawValue: type.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: lastAdvertTimestamp,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func createMessage(senderKeyPrefix: Data?, senderNodeName: String? = nil, channelIndex: UInt8? = nil) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: channelIndex == nil ? UUID() : nil,
      channelIndex: channelIndex,
      text: "Test",
      timestamp: 0,
      createdAt: Date(),
      direction: .incoming,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: senderKeyPrefix,
      senderNodeName: senderNodeName,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }

  // MARK: - senderName

  @Test
  func `sender name uses full key prefix match`() {
    let viewModel = MessagePathViewModel()

    let contactA = createContact(prefix: [0xAA, 0x00, 0x00, 0x00, 0x00, 0x00], name: "Alpha")
    let contactB = createContact(prefix: [0xAA, 0x01, 0x00, 0x00, 0x00, 0x00], name: "Bravo")

    viewModel.contacts = [contactA, contactB]

    let message = createMessage(senderKeyPrefix: contactB.publicKeyPrefix)

    #expect(viewModel.senderName(for: message) == "Bravo")
  }

  @Test
  func `sender resolution marks short prefix match as fallback`() {
    let viewModel = MessagePathViewModel()
    let older = createContact(prefix: [0xAA, 0x01], name: "Older", lastAdvertTimestamp: 100)
    let newer = createContact(prefix: [0xAA, 0x02], name: "Newer", lastAdvertTimestamp: 200)
    viewModel.contacts = [older, newer]

    let message = createMessage(senderKeyPrefix: Data([0xAA]))
    let result = viewModel.senderResolution(for: message)

    #expect(result.displayName == "Newer")
    #expect(result.matchKind == .fallback)
  }

  @Test
  func `sender resolution marks unique short prefix match as exact`() {
    let viewModel = MessagePathViewModel()
    let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha")
    viewModel.contacts = [contact]

    let message = createMessage(senderKeyPrefix: Data([0xAA]))
    let result = viewModel.senderResolution(for: message)

    #expect(result.displayName == "Alpha")
    #expect(result.matchKind == .exact)
  }

  @Test
  func `sender resolution marks full prefix match as exact`() {
    let viewModel = MessagePathViewModel()
    let contact = createContact(prefix: [0xAA, 0x01], name: "Alpha")
    viewModel.contacts = [contact]

    let message = createMessage(senderKeyPrefix: contact.publicKeyPrefix)
    let result = viewModel.senderResolution(for: message)

    #expect(result.displayName == "Alpha")
    #expect(result.matchKind == .exact)
  }

  @Test
  func `sender name returns channel sender node name for channel messages`() {
    let viewModel = MessagePathViewModel()
    let message = createMessage(senderKeyPrefix: nil, senderNodeName: "RemoteNode", channelIndex: 0)
    #expect(viewModel.senderName(for: message) == "RemoteNode")
  }

  @Test
  func `sender name returns unknown when no key prefix match`() {
    let viewModel = MessagePathViewModel()
    viewModel.contacts = [
      createContact(prefix: [0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF], name: "Alpha")
    ]

    let message = createMessage(senderKeyPrefix: Data([0x11, 0x22, 0x33, 0x44, 0x55, 0x66]))
    #expect(viewModel.senderName(for: message) == L10n.Chats.Chats.Path.Hop.unknown)
  }

  // MARK: - senderNodeID

  @Test
  func `senderNodeID returns hex of first prefix byte`() {
    let viewModel = MessagePathViewModel()
    let message = createMessage(senderKeyPrefix: Data([0xAB, 0xCD, 0xEF, 0x12]))
    #expect(viewModel.senderNodeID(for: message) == "AB")
  }

  @Test
  func `senderNodeID returns nil when no key prefix`() {
    let viewModel = MessagePathViewModel()
    let message = createMessage(senderKeyPrefix: nil)
    #expect(viewModel.senderNodeID(for: message) == nil)
  }

  @Test
  func `senderNodeID returns nil for empty key prefix`() {
    let viewModel = MessagePathViewModel()
    let message = createMessage(senderKeyPrefix: Data())
    #expect(viewModel.senderNodeID(for: message) == nil)
  }

  @Test
  func `senderNodeID formats leading zero correctly`() {
    let viewModel = MessagePathViewModel()
    let message = createMessage(senderKeyPrefix: Data([0x0A]))
    #expect(viewModel.senderNodeID(for: message) == "0A")
  }

  // MARK: - repeaterName

  @Test
  func `repeaterName returns unknown when no contacts match`() {
    let viewModel = MessagePathViewModel()
    viewModel.repeaters = []
    viewModel.discoveredRepeaters = []
    let name = viewModel.repeaterName(for: Data([0x01, 0x02]), userLocation: nil)
    #expect(name == L10n.Chats.Chats.Path.Hop.unknown)
  }

  // MARK: - loadContacts

  @Test
  func `loadContacts with nil dataStore sets isLoading false and clears data`() async {
    let viewModel = MessagePathViewModel()
    let contact = createContact(prefix: [0xAA], name: "Stale", type: .repeater)
    viewModel.contacts = [contact]
    viewModel.repeaters = [contact]
    viewModel.discoveredRepeaters = [
      DiscoveredNodeDTO(
        id: UUID(),
        radioID: UUID(),
        publicKey: Data([0xAA] + Array(repeating: UInt8(0), count: 31)),
        name: "StaleNode",
        typeRawValue: ContactType.repeater.rawValue,
        lastHeard: Date(),
        lastAdvertTimestamp: 0,
        latitude: 0,
        longitude: 0,
        outPathLength: 0,
        outPath: Data(),
        inboundHopCount: nil,
        inboundHopAdvertTimestamp: nil
      )
    ]
    #expect(viewModel.isLoading == true)

    await viewModel.loadContacts(dataStore: nil, radioID: UUID())

    #expect(viewModel.isLoading == false)
    #expect(viewModel.contacts.isEmpty)
    #expect(viewModel.repeaters.isEmpty)
    #expect(viewModel.discoveredRepeaters.isEmpty)
  }
}
