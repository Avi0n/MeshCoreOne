import Foundation
import MC1Services

/// Convert a `PendingSendDTO` back into the runtime envelope shape the
/// `SendQueue` actor consumes. Returns nil if the DTO's discriminator does
/// not match the requested envelope type — guards against mis-routing a
/// channel row into the DM queue or vice versa.
extension PendingSendDTO {

    func directMessageEnvelope() -> DirectMessageEnvelope? {
        guard kind == .dm, let contactID else { return nil }
        return DirectMessageEnvelope(messageID: messageID, contactID: contactID)
    }

    func channelMessageEnvelope() -> ChannelMessageEnvelope? {
        guard kind == .channel, let channelIndex else { return nil }
        return ChannelMessageEnvelope(
            messageID: messageID,
            channelIndex: channelIndex,
            isResend: isResend,
            messageText: messageText,
            messageTimestamp: messageTimestamp,
            localNodeName: localNodeName
        )
    }
}

/// Construct a `PendingSendDTO` from a runtime envelope. Used by the
/// `ChatViewModel` enqueue wrappers immediately before calling
/// `PersistenceStore.insertPendingSendAssigningSequence(_:)`.
///
/// `sequence` is initialized to 0 here; the production persist path
/// assigns the real value inside `PersistenceStore` so two concurrent
/// enqueues can't race to produce the same sequence. Tests that need
/// to pin sequence values should call the full positional
/// `PendingSendDTO.init(...)` directly.
extension PendingSendDTO {

    init(
        id: UUID = UUID(),
        envelope: DirectMessageEnvelope,
        radioID: UUID,
        enqueuedAt: Date = Date()
    ) {
        self.init(
            id: id,
            radioID: radioID,
            messageID: envelope.messageID,
            kind: .dm,
            contactID: envelope.contactID,
            channelIndex: nil,
            isResend: false,
            messageText: "",
            messageTimestamp: 0,
            localNodeName: nil,
            sequence: 0,
            enqueuedAt: enqueuedAt
        )
    }

    init(
        id: UUID = UUID(),
        envelope: ChannelMessageEnvelope,
        radioID: UUID,
        enqueuedAt: Date = Date()
    ) {
        self.init(
            id: id,
            radioID: radioID,
            messageID: envelope.messageID,
            kind: .channel,
            contactID: nil,
            channelIndex: envelope.channelIndex,
            isResend: envelope.isResend,
            messageText: envelope.messageText,
            messageTimestamp: envelope.messageTimestamp,
            localNodeName: envelope.localNodeName,
            sequence: 0,
            enqueuedAt: enqueuedAt
        )
    }
}
