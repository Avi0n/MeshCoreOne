import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("Notification quick reply radio ownership")
struct NotificationQuickReplyTests {
  @Test(arguments: [true, false], [true, false])
  @MainActor
  func `Channel quick replies dispatch only through the originating ready radio`(
    sameRadio: Bool,
    isReady: Bool
  ) async throws {
    let connectedRadioID = UUID()
    let notificationRadioID = sameRadio ? connectedRadioID : UUID()
    let transport = ReplyTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: connectedRadioID)
    let channel = ChannelDTO.testChannel(radioID: notificationRadioID, unreadCount: 2)
    try await services.dataStore.saveChannel(channel)
    services.notificationActionHandler.configure(
      isConnectionReady: { isReady && $0 == connectedRadioID },
      localNodeName: { nil }
    )

    await services.notificationActionHandler.handleChannelQuickReply(
      radioID: notificationRadioID,
      channelIndex: channel.index,
      text: "radio-switch-test"
    )

    let shouldDispatch = sameRadio && isReady
    let commands = await transport.messageCommands
    #expect(commands.count == (shouldDispatch ? 1 : 0))
    if shouldDispatch {
      #expect(commands.first?.first == CommandCode.sendChannelMessage.rawValue)
    }
    let messages = try await services.dataStore.fetchMessages(
      radioID: notificationRadioID, channelIndex: channel.index, limit: 10, offset: 0
    )
    #expect(messages.count == (shouldDispatch ? 1 : 0))
    let storedChannel = try #require(try await services.dataStore.fetchChannel(
      radioID: notificationRadioID, index: channel.index
    ))
    #expect(storedChannel.unreadCount == channel.unreadCount)
  }

  @Test(arguments: [true, false], [true, false])
  @MainActor
  func `Direct quick replies dispatch only through the originating ready radio`(
    sameRadio: Bool,
    isReady: Bool
  ) async throws {
    let connectedRadioID = UUID()
    let notificationRadioID = sameRadio ? connectedRadioID : UUID()
    let transport = ReplyTransport()
    let session = MeshCoreSession(transport: transport)
    try await session.start()
    defer { Task { await session.stop() } }

    let services = try await ServiceContainer.forTesting(session: session, radioID: connectedRadioID)
    let contact = ContactDTO.testContact(radioID: notificationRadioID, unreadCount: 2)
    try await services.dataStore.saveContact(contact)
    services.notificationActionHandler.configure(
      isConnectionReady: { isReady && $0 == connectedRadioID },
      localNodeName: { nil }
    )

    let reply = "radio-switch-test"
    await services.notificationActionHandler.handleQuickReply(contactID: contact.id, text: reply)

    let shouldDispatch = sameRadio && isReady
    let commands = await transport.messageCommands
    #expect(commands.count == (shouldDispatch ? 1 : 0))
    if shouldDispatch {
      #expect(commands.first?.first == CommandCode.sendMessage.rawValue)
    }
    let messages = try await services.dataStore.fetchMessages(contactID: contact.id, limit: 10, offset: 0)
    #expect(messages.count == (shouldDispatch ? 1 : 0))
    let storedContact = try #require(try await services.dataStore.fetchContact(id: contact.id))
    #expect(storedContact.unreadCount == contact.unreadCount)
    #expect(services.notificationService.consumeDraft(for: contact.id) == reply)
  }

  private actor ReplyTransport: MeshTransport {
    let receivedData: AsyncStream<Data>
    private let continuation: AsyncStream<Data>.Continuation
    private(set) var isConnected = false
    private(set) var messageCommands: [Data] = []

    init() {
      (receivedData, continuation) = AsyncStream.makeStream(of: Data.self)
    }

    func connect() {
      isConnected = true
    }

    func disconnect() {
      isConnected = false
      continuation.finish()
    }

    func send(_ data: Data) throws {
      if data.first == CommandCode.appStart.rawValue {
        continuation.yield(selfInfoPacket())
        return
      }
      messageCommands.append(data)
      // Fail after dispatch so these tests never call the OS notification center.
      throw MeshTransportError.sendFailed("Test transport send failure")
    }

    private func selfInfoPacket() -> Data {
      var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
      packet.append(Data(repeating: 0x01, count: 32))
      packet.append(Data(repeating: 0, count: 12))
      for value: UInt32 in [915_000, 125_000] {
        packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
      }
      packet.append(contentsOf: [7, 5])
      packet.append(contentsOf: "Test".utf8)
      return packet
    }
  }
}
