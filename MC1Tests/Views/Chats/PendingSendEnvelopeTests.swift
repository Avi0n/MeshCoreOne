import Foundation
@testable import MC1
@testable import MC1Services
import Testing

struct PendingSendEnvelopeTests {
  @Test
  func `DM envelope round-trips through DTO`() {
    let radio = UUID()
    let envelope = DirectMessageEnvelope(messageID: UUID(), contactID: UUID())
    let dto = PendingSendDTO(envelope: envelope, radioID: radio)
    let recovered = dto.directMessageEnvelope()
    #expect(recovered?.messageID == envelope.messageID)
    #expect(recovered?.contactID == envelope.contactID)
    #expect(dto.channelMessageEnvelope() == nil)
  }

  @Test
  func `Channel envelope round-trips through DTO`() {
    let radio = UUID()
    let envelope = ChannelMessageEnvelope(
      messageID: UUID(),
      channelIndex: 5,
      isResend: true,
      messageText: "hi",
      messageTimestamp: 1_700_000_000,
      localNodeName: "Bob"
    )
    let dto = PendingSendDTO(envelope: envelope, radioID: radio)
    let recovered = dto.channelMessageEnvelope()
    #expect(recovered?.messageID == envelope.messageID)
    #expect(recovered?.channelIndex == envelope.channelIndex)
    #expect(recovered?.isResend == envelope.isResend)
    #expect(recovered?.messageText == envelope.messageText)
    #expect(recovered?.localNodeName == envelope.localNodeName)
    #expect(dto.directMessageEnvelope() == nil)
  }
}
