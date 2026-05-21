import Foundation

/// Convert a `PendingSendDTO` back into the runtime envelope shape the
/// `SendQueue` actor consumes. Returns nil if the DTO's discriminator does
/// not match the requested envelope type — guards against mis-routing a
/// channel row into the DM queue or vice versa.
public extension PendingSendDTO {

    func directMessageEnvelope() -> DirectMessageEnvelope? {
        guard kind == .dm, let contactID else { return nil }
        return DirectMessageEnvelope(messageID: messageID, contactID: contactID, isResend: isResend)
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
/// chat send queue service immediately before calling
/// `PersistenceStore.insertPendingSendAssigningSequence(_:)`.
///
/// `sequence` is initialized to 0 here; the production persist path
/// assigns the real value inside `PersistenceStore` so two concurrent
/// enqueues can't race to produce the same sequence. Tests that need
/// to pin sequence values should call the full positional
/// `PendingSendDTO.init(...)` directly.
public extension PendingSendDTO {

    init(
        id: UUID = UUID(),
        envelope: DirectMessageEnvelope,
        radioID: UUID,
        enqueuedAt: Date = Date()
    ) {
        // DM rows persist `isResend` so a process restart between enqueue
        // and drain routes the row to the same send method it would have
        // hit pre-restart (sendPendingDirectMessage vs. resendDirectMessage).
        // `messageTimestamp` stays sentinel: the DM drain keys preserve-
        // timestamp behaviour off `PendingSend.attemptCount` via the
        // top-of-drain bump (`postBumpCount > 1` returns true on the 2nd+
        // drain attempt), so the wire timestamp does not round-trip
        // through the DTO. Channel rows persist both explicitly because
        // reaction indexing hashes off the post-send wire timestamp.
        self.init(
            id: id,
            radioID: radioID,
            messageID: envelope.messageID,
            kind: .dm,
            contactID: envelope.contactID,
            channelIndex: nil,
            isResend: envelope.isResend,
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
        // ChannelMessageEnvelope DTO factory captures `envelope.messageTimestamp`
        // verbatim. For isResend=true rows the stored timestamp is overwritten
        // before use — `resendChannelMessage(preserveTimestamp: postBumpCount > 1)`
        // stamps a fresh wire timestamp when this is the first drain attempt
        // (`postBumpCount == 1`), and reaction indexing keys off the
        // post-resend value, not the persisted one. Kept non-zero on resend
        // rows for symmetry with original-send rows; readers must not depend
        // on the value for retry-path semantics.
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
