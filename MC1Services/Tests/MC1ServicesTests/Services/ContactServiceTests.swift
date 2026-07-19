import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ContactService Tests")
struct ContactServiceTests {
  // MARK: - Test Constants

  // Sync result test values
  private let testContactsReceived = 5
  private let testSyncTimestamp: UInt32 = 1_234_567_890
  private let maxContactsReceived = Int.max
  private let maxSyncTimestamp = UInt32.max

  /// Contact test values
  private let testPublicKey = Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
                                    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10,
                                    0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
                                    0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20])
  private let testTimestamp: UInt32 = 1_700_000_000
  private let testModifiedTimestamp: UInt32 = 1_700_000_100
  private let testFlags: UInt8 = 0x01
  private let invalidContactType: UInt8 = 0xFF
  private let testOutPath = Data([0xAA, 0xBB, 0xCC, 0xDD])
  private let floodRoutingPath = Data(repeating: 0xFF, count: 3)

  // MARK: - ContactSyncResult Tests

  @Test
  func `ContactSyncResult initializes correctly`() {
    let result = ContactSyncResult(
      contactsReceived: testContactsReceived,
      lastSyncTimestamp: testSyncTimestamp,
      isIncremental: true
    )
    #expect(result.contactsReceived == testContactsReceived)
    #expect(result.lastSyncTimestamp == testSyncTimestamp)
    #expect(result.isIncremental == true)
  }

  @Test
  func `ContactSyncResult handles zero contacts`() {
    let result = ContactSyncResult(
      contactsReceived: 0,
      lastSyncTimestamp: 0,
      isIncremental: false
    )
    #expect(result.contactsReceived == 0)
    #expect(result.lastSyncTimestamp == 0)
    #expect(result.isIncremental == false)
  }

  @Test
  func `ContactSyncResult handles maximum values`() {
    let result = ContactSyncResult(
      contactsReceived: maxContactsReceived,
      lastSyncTimestamp: maxSyncTimestamp,
      isIncremental: true
    )
    #expect(result.contactsReceived == maxContactsReceived)
    #expect(result.lastSyncTimestamp == maxSyncTimestamp)
    #expect(result.isIncremental == true)
  }

  // MARK: - ContactServiceError Tests

  @Test
  func `ContactServiceError cases are distinct`() {
    // Verify basic error cases
    let basicErrors: [ContactServiceError] = [
      .notConnected,
      .sendFailed,
      .invalidResponse,
      .syncInterrupted,
      .contactNotFound,
      .contactTableFull
    ]

    // Verify all basic cases are distinct (no duplicates)
    let errorDescriptions = basicErrors.map { String(describing: $0) }
    let uniqueDescriptions = Set(errorDescriptions)
    #expect(errorDescriptions.count == uniqueDescriptions.count)
  }

  // MARK: - MeshContact.toContactFrame() Tests

  @Test
  func `MeshContact converts to ContactFrame correctly`() {
    // Create a test MeshContact with all fields populated
    let publicKey = testPublicKey
    let outPath = testOutPath
    let advertisedName = "TestNode"
    let lastAdvertDate = Date(timeIntervalSince1970: TimeInterval(testTimestamp))
    let lastModifiedDate = Date(timeIntervalSince1970: TimeInterval(testModifiedTimestamp))
    let latitude = 37.7749
    let longitude = -122.4194

    let meshContact = MeshContact(
      id: publicKey.uppercaseHexString(),
      publicKey: publicKey,
      type: .chat,
      flags: ContactFlags(rawValue: testFlags),
      outPathLength: 2,
      outPath: outPath,
      advertisedName: advertisedName,
      lastAdvertisement: lastAdvertDate,
      latitude: latitude,
      longitude: longitude,
      lastModified: lastModifiedDate
    )

    // Convert to ContactFrame
    let contactFrame = meshContact.toContactFrame()

    // Verify all fields are correctly mapped
    #expect(contactFrame.publicKey == publicKey)
    #expect(contactFrame.type == .chat)
    #expect(contactFrame.flags == testFlags)
    #expect(contactFrame.outPathLength == 2)
    #expect(contactFrame.outPath == outPath)
    #expect(contactFrame.name == advertisedName)
    #expect(contactFrame.lastAdvertTimestamp == UInt32(lastAdvertDate.timeIntervalSince1970))
    #expect(contactFrame.latitude == latitude)
    #expect(contactFrame.longitude == longitude)
    #expect(contactFrame.lastModified == UInt32(lastModifiedDate.timeIntervalSince1970))
  }

  @Test
  func `MeshContact handles all ContactType conversions`() {
    let publicKey = Data(repeating: 0x00, count: ProtocolLimits.publicKeySize)

    // Test chat type
    let chatContact = MeshContact(
      id: publicKey.uppercaseHexString(),
      publicKey: publicKey,
      type: .chat,
      flags: ContactFlags(rawValue: 0),
      outPathLength: 0,
      outPath: Data(),
      advertisedName: "Chat",
      lastAdvertisement: Date(),
      latitude: 0,
      longitude: 0,
      lastModified: Date()
    )
    #expect(chatContact.toContactFrame().type == .chat)

    // Test repeater type
    let repeaterContact = MeshContact(
      id: publicKey.uppercaseHexString(),
      publicKey: publicKey,
      type: .repeater,
      flags: ContactFlags(rawValue: 0),
      outPathLength: 0,
      outPath: Data(),
      advertisedName: "Repeater",
      lastAdvertisement: Date(),
      latitude: 0,
      longitude: 0,
      lastModified: Date()
    )
    #expect(repeaterContact.toContactFrame().type == .repeater)

    // Test room type
    let roomContact = MeshContact(
      id: publicKey.uppercaseHexString(),
      publicKey: publicKey,
      type: .room,
      flags: ContactFlags(rawValue: 0),
      outPathLength: 0,
      outPath: Data(),
      advertisedName: "Room",
      lastAdvertisement: Date(),
      latitude: 0,
      longitude: 0,
      lastModified: Date()
    )
    #expect(roomContact.toContactFrame().type == .room)
  }

  @Test
  func `Parser handles invalid ContactType by defaulting to .chat`() {
    // Build 147-byte contact data with invalid type byte at offset 32
    var data = Data(repeating: 0x00, count: 147)
    data[32] = invalidContactType // type byte

    // Parser should default unknown types to .chat
    let contact = Parsers.parseContactData(data)
    #expect(contact?.type == .chat)
  }

  @Test
  func `MeshContact handles flood routing path`() {
    let publicKey = Data(repeating: 0x00, count: ProtocolLimits.publicKeySize)

    let floodContact = MeshContact(
      id: publicKey.uppercaseHexString(),
      publicKey: publicKey,
      type: .chat,
      flags: ContactFlags(rawValue: 0),
      outPathLength: 0xFF, // Flood routing
      outPath: Data(),
      advertisedName: "Flood",
      lastAdvertisement: Date(),
      latitude: 0,
      longitude: 0,
      lastModified: Date()
    )

    let frame = floodContact.toContactFrame()
    #expect(frame.outPathLength == 0xFF)
    #expect(frame.outPath.isEmpty)
  }

  // MARK: - ContactFrame.toMeshContact() Tests

  @Test
  func `ContactFrame converts to MeshContact correctly`() {
    // Create a test ContactFrame with all fields populated
    let publicKey = testPublicKey
    let outPath = testOutPath
    let name = "TestNode"
    let lastAdvertTimestamp = testTimestamp
    let lastModified = testModifiedTimestamp
    let latitude = 37.7749
    let longitude = -122.4194

    let contactFrame = ContactFrame(
      publicKey: publicKey,
      type: .chat,
      flags: testFlags,
      outPathLength: 2,
      outPath: outPath,
      name: name,
      lastAdvertTimestamp: lastAdvertTimestamp,
      latitude: latitude,
      longitude: longitude,
      lastModified: lastModified
    )

    // Convert to MeshContact
    let meshContact = contactFrame.toMeshContact()

    // Verify all fields are correctly mapped
    #expect(meshContact.id == publicKey.uppercaseHexString())
    #expect(meshContact.publicKey == publicKey)
    #expect(meshContact.type == .chat)
    #expect(meshContact.flags == ContactFlags(rawValue: testFlags))
    #expect(meshContact.outPathLength == 2)
    #expect(meshContact.outPath == outPath)
    #expect(meshContact.advertisedName == name)
    #expect(meshContact.lastAdvertisement == Date(timeIntervalSince1970: TimeInterval(lastAdvertTimestamp)))
    #expect(meshContact.latitude == latitude)
    #expect(meshContact.longitude == longitude)
    #expect(meshContact.lastModified == Date(timeIntervalSince1970: TimeInterval(lastModified)))
  }

  @Test
  func `ContactFrame ID generation from public key`() {
    let publicKey = Data([0xAB, 0xCD, 0xEF, 0x12, 0x34, 0x56, 0x78, 0x90,
                          0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
                          0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00,
                          0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08])

    let contactFrame = ContactFrame(
      publicKey: publicKey,
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Test",
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )

    let meshContact = contactFrame.toMeshContact()

    // ID should be hex string of public key (uppercase)
    let expectedID = publicKey.uppercaseHexString()
    #expect(meshContact.id == expectedID)
  }

  @Test
  func `ContactFrame handles all ContactType conversions`() {
    let publicKey = Data(repeating: 0x00, count: ProtocolLimits.publicKeySize)

    // Test chat type
    let chatFrame = ContactFrame(
      publicKey: publicKey,
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Chat",
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    #expect(chatFrame.toMeshContact().type == .chat)

    // Test repeater type
    let repeaterFrame = ContactFrame(
      publicKey: publicKey,
      type: .repeater,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Repeater",
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    #expect(repeaterFrame.toMeshContact().type == .repeater)

    // Test room type
    let roomFrame = ContactFrame(
      publicKey: publicKey,
      type: .room,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Room",
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0
    )
    #expect(roomFrame.toMeshContact().type == .room)
  }

  // MARK: - Round-Trip Conversion Tests

  @Test
  func `Round-trip conversion MeshContact -> ContactFrame -> MeshContact`() {
    let publicKey = testPublicKey

    let original = MeshContact(
      id: publicKey.uppercaseHexString(),
      publicKey: publicKey,
      type: .repeater,
      flags: ContactFlags(rawValue: 0x05),
      outPathLength: 3,
      outPath: Data([0xAA, 0xBB, 0xCC]),
      advertisedName: "OriginalNode",
      lastAdvertisement: Date(timeIntervalSince1970: TimeInterval(testTimestamp)),
      latitude: 40.7128,
      longitude: -74.0060,
      lastModified: Date(timeIntervalSince1970: 1_700_000_200)
    )

    // Convert to ContactFrame and back
    let frame = original.toContactFrame()
    let roundTripped = frame.toMeshContact()

    // Verify all fields survived the round trip
    #expect(roundTripped.id == original.id)
    #expect(roundTripped.publicKey == original.publicKey)
    #expect(roundTripped.type == original.type)
    #expect(roundTripped.flags == original.flags)
    #expect(roundTripped.outPathLength == original.outPathLength)
    #expect(roundTripped.outPath == original.outPath)
    #expect(roundTripped.advertisedName == original.advertisedName)
    #expect(roundTripped.lastAdvertisement == original.lastAdvertisement)
    #expect(roundTripped.latitude == original.latitude)
    #expect(roundTripped.longitude == original.longitude)
    #expect(roundTripped.lastModified == original.lastModified)
  }

  @Test
  func `Round-trip conversion ContactFrame -> MeshContact -> ContactFrame`() {
    let publicKey = testPublicKey

    let original = ContactFrame(
      publicKey: publicKey,
      type: .room,
      flags: 0x03,
      outPathLength: 1,
      outPath: Data([0xFF]),
      name: "OriginalRoom",
      lastAdvertTimestamp: testTimestamp,
      latitude: 51.5074,
      longitude: -0.1278,
      lastModified: 1_700_000_300
    )

    // Convert to MeshContact and back
    let meshContact = original.toMeshContact()
    let roundTripped = meshContact.toContactFrame()

    // Verify all fields survived the round trip
    #expect(roundTripped == original)
  }

  // MARK: - Cleanup Coordinator Tests

  /// Actor to track cleanup invocations in a thread-safe manner
  private actor CleanupTracker {
    var invocations: [(contactID: UUID, reason: ContactCleanupReason, publicKey: Data)] = []

    func record(contactID: UUID, reason: ContactCleanupReason, publicKey: Data) {
      invocations.append((contactID: contactID, reason: reason, publicKey: publicKey))
    }
  }

  /// Cleanup coordinator stand-in that records each invocation on a `CleanupTracker`.
  private struct RecordingCleanupCoordinator: ContactCleanupHandling {
    let tracker: CleanupTracker

    func handleCleanup(contactID: UUID, reason: ContactCleanupReason, publicKey: Data) async {
      await tracker.record(contactID: contactID, reason: reason, publicKey: publicKey)
    }
  }

  @Test
  func `removeContact deletes messages and triggers cleanup`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()

    let radioID = UUID()
    let contactID = UUID()

    // Set up contact in the mock store
    let contact = ContactDTO(
      id: contactID,
      radioID: radioID,
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
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
      unreadCount: 3
    )
    try await mockStore.saveContact(contact)

    // Track cleanup handler invocations
    let tracker = CleanupTracker()

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: RecordingCleanupCoordinator(tracker: tracker)
    )

    // Seed a message so the delete cascade is observable
    let message = MessageDTO(from: Message(
      radioID: radioID,
      contactID: contactID,
      text: "cascade",
      timestamp: 0
    ))
    try await mockStore.saveMessage(message)

    // Remove the contact
    try await service.removeContact(radioID: radioID, publicKey: testPublicKey)

    // Verify the contact was deleted and its messages died with it
    let deletedContacts = await mockStore.deletedContactIDs
    #expect(deletedContacts == [contactID])
    let remainingMessages = await mockStore.messages
    #expect(remainingMessages.isEmpty)

    // Verify cleanup handler was called with reason=.deleted
    let invocations = await tracker.invocations
    #expect(invocations.count == 1)
    #expect(invocations[0].contactID == contactID)
    #expect(invocations[0].reason == .deleted)
  }

  @Test
  func `clearContactMessages deletes messages and zeroes both unread counters while preserving lastMessageDate`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()

    let radioID = UUID()
    let contactID = UUID()
    let lastMessageDate = Date(timeIntervalSince1970: TimeInterval(testTimestamp))

    let contact = ContactDTO(
      id: contactID,
      radioID: radioID,
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: lastMessageDate,
      unreadCount: 4,
      unreadMentionCount: 2
    )
    try await mockStore.saveContact(contact)

    // Seed real message rows so deletion is observable, not just forwarded.
    for offset in 0..<3 {
      try await mockStore.saveMessage(MessageDTO(
        id: UUID(),
        radioID: radioID,
        contactID: contactID,
        channelIndex: nil,
        text: "msg \(offset)",
        timestamp: testTimestamp + UInt32(offset),
        createdAt: lastMessageDate,
        direction: .incoming,
        status: .delivered,
        textType: .plain,
        ackCode: nil,
        pathLength: 0,
        snr: nil,
        senderKeyPrefix: nil,
        senderNodeName: "TestContact",
        isRead: false,
        replyToID: nil,
        roundTripTime: nil,
        heardRepeats: 0,
        retryAttempt: 0,
        maxRetryAttempts: 0
      ))
    }

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    try await service.clearContactMessages(contactID: contactID)

    // Messages are actually gone, not merely reported deleted.
    let deletedForContacts = await mockStore.deletedMessagesForContactIDs
    #expect(deletedForContacts == [contactID])
    let remaining = try await mockStore.fetchMessages(contactID: contactID, limit: 10, offset: 0)
    #expect(remaining.isEmpty)

    // The conversation stays listed: lastMessageDate is preserved, both unread counters zeroed.
    let updated = try await mockStore.fetchContact(id: contactID)
    #expect(updated?.lastMessageDate == lastMessageDate)
    #expect(updated?.unreadCount == 0)
    #expect(updated?.unreadMentionCount == 0)
  }

  @Test
  func `updateContactPreferences clears unread when blocking`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()

    let radioID = UUID()
    let contactID = UUID()

    // Set up contact with unread count
    let contact = ContactDTO(
      id: contactID,
      radioID: radioID,
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
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
      unreadCount: 5
    )
    try await mockStore.saveContact(contact)

    // Track cleanup handler invocations
    let tracker = CleanupTracker()

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: RecordingCleanupCoordinator(tracker: tracker)
    )

    // Block the contact
    try await service.updateContactPreferences(contactID: contactID, isBlocked: true)

    // Verify unread count was cleared
    let updatedContact = await mockStore.contacts[contactID]
    #expect(updatedContact?.unreadCount == 0)
    #expect(updatedContact?.isBlocked == true)

    // Verify cleanup handler was called with reason=.blocked
    let invocations = await tracker.invocations
    #expect(invocations.count == 1)
    #expect(invocations[0].contactID == contactID)
    #expect(invocations[0].reason == .blocked)
  }

  @Test
  func `updateContactAvatar sets and clears avatarImageData`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()

    let radioID = UUID()
    let contactID = UUID()

    let contact = ContactDTO(
      id: contactID,
      radioID: radioID,
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
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
      unreadCount: 0
    )
    try await mockStore.saveContact(contact)

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    let imageData = Data([0xDE, 0xAD, 0xBE, 0xEF])
    try await service.updateContactAvatar(contactID: contactID, imageData: imageData)

    let withAvatar = await mockStore.contacts[contactID]
    #expect(withAvatar?.avatarImageData == imageData)

    try await service.updateContactAvatar(contactID: contactID, imageData: nil)

    let withoutAvatar = await mockStore.contacts[contactID]
    #expect(withoutAvatar?.avatarImageData == nil)
  }

  @Test
  func `updateContactPreferences does not trigger cleanup when not blocking`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()

    let radioID = UUID()
    let contactID = UUID()

    // Set up contact
    let contact = ContactDTO(
      id: contactID,
      radioID: radioID,
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
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
      unreadCount: 5
    )
    try await mockStore.saveContact(contact)

    // Track cleanup handler invocations
    let tracker = CleanupTracker()

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: RecordingCleanupCoordinator(tracker: tracker)
    )

    // Update nickname (not blocking)
    try await service.updateContactPreferences(contactID: contactID, nickname: "NewNickname")

    // Verify unread count was preserved
    let updatedContact = await mockStore.contacts[contactID]
    #expect(updatedContact?.unreadCount == 5)
    #expect(updatedContact?.nickname == "NewNickname")

    // Verify cleanup handler was NOT called
    let invocations = await tracker.invocations
    #expect(invocations.isEmpty)
  }

  @Test
  func `updateContactPreferences preserves fields when blocking`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()

    let radioID = UUID()
    let contactID = UUID()

    // Set up contact with special fields
    let contact = ContactDTO(
      id: contactID,
      radioID: radioID,
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: "MyNickname",
      isBlocked: false,
      isMuted: false,
      isFavorite: true,
      lastMessageDate: Date(),
      unreadCount: 5,
      ocvPreset: "medium",
      customOCVArrayString: "custom"
    )
    try await mockStore.saveContact(contact)

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    // Block the contact
    try await service.updateContactPreferences(contactID: contactID, isBlocked: true)

    // Verify all fields are preserved except unreadCount
    let updatedContact = await mockStore.contacts[contactID]
    #expect(updatedContact?.nickname == "MyNickname")
    #expect(updatedContact?.isFavorite == true)
    #expect(updatedContact?.ocvPreset == "medium")
    #expect(updatedContact?.customOCVArrayString == "custom")
    #expect(updatedContact?.unreadCount == 0)
    #expect(updatedContact?.isBlocked == true)
  }

  @Test
  func `unblocking contact triggers cleanup with unblocked reason`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()

    let radioID = UUID()
    let contactID = UUID()

    // Set up contact that is already blocked
    let contact = ContactDTO(
      id: contactID,
      radioID: radioID,
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: true,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    try await mockStore.saveContact(contact)

    // Track cleanup handler invocations
    let tracker = CleanupTracker()

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: RecordingCleanupCoordinator(tracker: tracker)
    )

    // Unblock the contact
    try await service.updateContactPreferences(contactID: contactID, isBlocked: false)

    // Verify contact was unblocked
    let updatedContact = await mockStore.contacts[contactID]
    #expect(updatedContact?.isBlocked == false)

    // Verify cleanup handler was called with reason=.unblocked
    let invocations = await tracker.invocations
    #expect(invocations.count == 1)
    #expect(invocations[0].contactID == contactID)
    #expect(invocations[0].reason == .unblocked)
  }

  // MARK: - Reset Path

  @Test
  func `resetPath flood-routes the contact while preserving an unmodeled type byte`() async throws {
    let radioID = UUID()
    // The real store round-trips the raw type byte; the mock store normalizes it, so this uses
    // a real PersistenceStore to observe what resetPath actually persists.
    let dataStore = try await PersistenceStore.createTestDataStore(radioID: radioID)
    let unmodeledType: UInt8 = 0x7F
    let contact = ContactDTO.testContact(
      radioID: radioID,
      publicKey: testPublicKey,
      typeRawValue: unmodeledType,
      outPathLength: 2,
      outPath: testOutPath
    )
    try await dataStore.saveContact(contact)

    let session = MockMeshCoreSession()
    let service = ContactService(
      session: session,
      dataStore: dataStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    try await service.resetPath(radioID: radioID, publicKey: testPublicKey)

    #expect(await session.resetPathPublicKeys == [testPublicKey])
    let reset = try await dataStore.fetchContact(radioID: radioID, publicKey: testPublicKey)
    #expect(reset?.isFloodRouted == true)
    #expect(reset?.typeRawValue == unmodeledType)
  }

  @Test
  func `updateContactPreferences clears nickname when passed empty string`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()
    let contactID = UUID()

    let contact = ContactDTO(
      id: contactID,
      radioID: UUID(),
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: "OldNickname",
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    try await mockStore.saveContact(contact)

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    try await service.updateContactPreferences(contactID: contactID, nickname: "")

    let updated = await mockStore.contacts[contactID]
    #expect(updated?.nickname == nil)
  }

  @Test
  func `updateContactPreferences clears nickname when passed whitespace only`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()
    let contactID = UUID()

    let contact = ContactDTO(
      id: contactID,
      radioID: UUID(),
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: "OldNickname",
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    try await mockStore.saveContact(contact)

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    try await service.updateContactPreferences(contactID: contactID, nickname: "   ")

    let updated = await mockStore.contacts[contactID]
    #expect(updated?.nickname == nil)
  }

  @Test
  func `updateContactPreferences trims surrounding whitespace from nickname`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()
    let contactID = UUID()

    let contact = ContactDTO(
      id: contactID,
      radioID: UUID(),
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
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
      unreadCount: 0
    )
    try await mockStore.saveContact(contact)

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    try await service.updateContactPreferences(contactID: contactID, nickname: "  Rico  ")

    let updated = await mockStore.contacts[contactID]
    #expect(updated?.nickname == "Rico")
  }

  @Test
  func `updateContactPreferences keeps nickname when nickname arg is nil`() async throws {
    let mockSession = MockMeshCoreSession()
    let mockStore = MockPersistenceStore()
    let contactID = UUID()

    let contact = ContactDTO(
      id: contactID,
      radioID: UUID(),
      publicKey: testPublicKey,
      name: "TestContact",
      typeRawValue: ContactType.chat.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: "KeepMe",
      isBlocked: false,
      isMuted: true,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 5
    )
    try await mockStore.saveContact(contact)

    let service = ContactService(
      session: mockSession,
      dataStore: mockStore,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )

    // Toggle favorite without touching nickname; nickname must survive.
    try await service.updateContactPreferences(contactID: contactID, isFavorite: true)

    let updated = await mockStore.contacts[contactID]
    #expect(updated?.nickname == "KeepMe")
    #expect(updated?.isFavorite == true)
    // A preferences edit must not silently reset unrelated persisted fields.
    #expect(updated?.isMuted == true)
    #expect(updated?.unreadMentionCount == 5)
  }
}
