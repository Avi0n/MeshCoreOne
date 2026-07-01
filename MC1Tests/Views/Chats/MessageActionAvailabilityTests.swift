import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MessageActionAvailability")
struct MessageActionAvailabilityTests {
  // MARK: - canViewPath

  @Test
  func `flood-routed with non-empty pathNodes returns true`() {
    let message = makeMessage(pathNodes: Data([0xA3, 0x7F]), routeType: .flood)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canViewPath == true)
  }

  @Test
  func `flood-routed with empty pathNodes returns false`() {
    let message = makeMessage(pathNodes: Data(), routeType: .flood)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canViewPath == false)
  }

  @Test
  func `flood-routed with nil pathNodes returns false`() {
    let message = makeMessage(pathNodes: nil, routeType: .flood)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canViewPath == false)
  }

  @Test
  func `direct-routed returns false`() {
    let message = makeMessage(pathNodes: Data([0xA3, 0x7F]), routeType: .direct)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canViewPath == false)
  }

  @Test
  func `outgoing message returns false`() {
    let message = makeMessage(pathNodes: Data([0xA3, 0x7F]), direction: .outgoing, routeType: .flood)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canViewPath == false)
  }

  @Test
  func `channel message with routeType .direct is still flood-routed (channelIndex overrides)`() {
    let message = makeMessage(
      channelIndex: 0,
      pathNodes: Data([0xA3, 0x7F]),
      routeType: .direct
    )
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canViewPath == true)
  }

  // MARK: - isFloodRouted

  @Test
  func `.flood routeType is flood-routed`() {
    let message = makeMessage(routeType: .flood)
    #expect(message.isFloodRouted == true)
  }

  @Test
  func `.tcFlood routeType is flood-routed`() {
    let message = makeMessage(routeType: .tcFlood)
    #expect(message.isFloodRouted == true)
  }

  @Test
  func `.direct routeType is not flood-routed`() {
    let message = makeMessage(routeType: .direct)
    #expect(message.isFloodRouted == false)
  }

  @Test
  func `.tcDirect routeType is not flood-routed`() {
    let message = makeMessage(routeType: .tcDirect)
    #expect(message.isFloodRouted == false)
  }

  @Test
  func `unknown routeType with channelIndex is flood-routed`() {
    let message = makeMessage(channelIndex: 0, routeType: nil)
    #expect(message.isFloodRouted == true)
  }

  @Test
  func `unknown routeType with pathLength 0xFF is direct-routed`() {
    let message = makeMessage(pathLength: 0xFF, routeType: nil)
    #expect(message.isDirectRouted == true)
  }

  @Test
  func `unknown routeType with non-0xFF pathLength is flood-routed`() {
    let message = makeMessage(pathLength: 0x02, routeType: nil)
    #expect(message.isFloodRouted == true)
  }

  // MARK: - isDirectRouted

  @Test
  func `.direct routeType is direct-routed`() {
    let message = makeMessage(routeType: .direct)
    #expect(message.isDirectRouted == true)
  }

  @Test
  func `.tcDirect routeType is direct-routed`() {
    let message = makeMessage(routeType: .tcDirect)
    #expect(message.isDirectRouted == true)
  }

  // MARK: - canSendDM

  @Test
  func `channel incoming message with sender name returns true`() {
    let message = makeMessage(channelIndex: 0, direction: .incoming)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canSendDM == true)
  }

  @Test
  func `channel outgoing message returns false`() {
    let message = makeMessage(channelIndex: 0, direction: .outgoing)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canSendDM == false)
  }

  @Test
  func `DM incoming message returns false`() {
    let message = makeMessage(channelIndex: nil, direction: .incoming)
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canSendDM == false)
  }

  @Test
  func `channel incoming prefix-only message cannot send DM`() {
    let message = makeMessage(
      channelIndex: 0,
      direction: .incoming,
      senderKeyPrefix: Data([0xAA])
    )
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canSendDM == false)
  }

  @Test
  func `channel incoming prefix-only message cannot block sender`() {
    let message = makeMessage(
      channelIndex: 0,
      direction: .incoming,
      senderKeyPrefix: Data([0xAA])
    )
    let availability = MessageActionAvailability(message: message)
    #expect(availability.canBlockSender == false)
  }

  // MARK: - Helper

  private func makeMessage(
    contactID: UUID? = nil,
    channelIndex: UInt8? = nil,
    pathLength: UInt8 = 0x02,
    pathNodes: Data? = Data([0xA3, 0x7F]),
    direction: MessageDirection = .incoming,
    routeType: RouteType? = nil,
    senderKeyPrefix: Data? = nil
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: contactID,
      channelIndex: channelIndex,
      text: "Test",
      timestamp: 0,
      createdAt: Date(),
      direction: direction,
      status: .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: pathLength,
      snr: nil,
      pathNodes: pathNodes,
      senderKeyPrefix: senderKeyPrefix,
      senderNodeName: senderKeyPrefix == nil && channelIndex != nil ? "RemoteNode" : nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0,
      routeType: routeType
    )
  }
}
