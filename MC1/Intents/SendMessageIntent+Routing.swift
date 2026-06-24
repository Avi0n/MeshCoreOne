import Foundation
import MC1Services

extension SendMessageIntent {

    /// Classifies a send purely from the connection rung. `.connected` is
    /// deliberately treated as not-yet-ready (services are built after the rung
    /// flips to `.connected`), so it never takes the queue path.
    static func route(for state: DeviceConnectionState) -> SendRoute {
        switch state {
        case .ready: .headlessQueue
        case .syncing: .queueAfterSync
        case .connected, .connecting: .foregroundEscalate
        case .disconnected: .notConnected
        }
    }

    /// Splits the disconnected rung on whether a prior radio is restorable: with
    /// a last-connected radio the send foreground-escalates so the app reconnects;
    /// with nothing to restore, retrying cannot help, so the send throws.
    static func disconnectedRoute(hasRestorableRadio: Bool) -> SendRoute {
        hasRestorableRadio ? .foregroundEscalate : .notConnected
    }

    /// Rejects a message that cannot be sent to the chosen recipient: only a chat
    /// contact can receive a DM (a repeater or room cannot), and channel text is
    /// capped at the node-name-adjusted budget. The firmware prepends
    /// `"<NodeName>: "` to every channel broadcast, so the usable text is the
    /// total length minus the node name and its separator; validating against the
    /// full total would let a near-limit message pass and then be silently
    /// truncated on the air. DM length is enforced downstream by
    /// `createPendingMessage`.
    static func validate(message: String, for recipient: MessageRecipient, nodeNameByteCount: Int) throws {
        switch recipient {
        case .contact(let dto):
            guard dto.type == .chat else { throw IntentError.invalidRecipient }
        case .channel:
            let maxBytes = ProtocolLimits.maxChannelMessageLength(nodeNameByteCount: nodeNameByteCount)
            guard message.utf8.count <= maxBytes else {
                throw IntentError.messageTooLong
            }
        }
    }

    /// The confirmation prompt naming the recipient, spoken before the send so a
    /// broadcast under the user's identity is always deliberate.
    static func confirmText(for recipient: MessageRecipient) -> String {
        L10n.Tools.Intent.Send.confirm(recipientName(for: recipient))
    }

    /// The post-enqueue dialog. It only ever says "queued"; the enqueue returns
    /// before the radio ACK (DMs) and a channel broadcast has no ACK, so neither
    /// "sent" nor "delivered" is ever claimed here. `afterSync` reflects the route
    /// that classified this send, not a fresh state read, so the spoken line stays
    /// consistent with the path actually taken.
    static func queuedDialog(for recipient: MessageRecipient, afterSync: Bool) -> String {
        let name = recipientName(for: recipient)
        if afterSync {
            return L10n.Tools.Intent.Send.Dialog.queuedAfterSync(name)
        }
        switch recipient {
        case .contact: return L10n.Tools.Intent.Send.Dialog.queuedDM(name)
        case .channel: return L10n.Tools.Intent.Send.Dialog.queuedChannel(name)
        }
    }

    /// Rewraps a reused-service error into a localized `IntentError` so Siri
    /// never speaks a raw `MessageServiceError`/`ChannelServiceError`/
    /// `ChatSendQueueServiceError`. A failure to persist or enqueue a connected
    /// send maps to `.sendFailed`, never `.notConnected`, so the spoken line
    /// doesn't blame the connection for a local write that failed.
    static func mapToIntentError(_ error: Error) -> IntentError {
        switch error {
        case let intentError as IntentError:
            return intentError
        case let serviceError as MessageServiceError:
            switch serviceError {
            case .notConnected: return .notConnected
            case .invalidRecipient, .contactNotFound, .channelNotFound: return .invalidRecipient
            case .messageTooLong: return .messageTooLong
            case .sessionError(let underlying): return .sessionError(underlying)
            case .sendFailed: return .sendFailed
            }
        case let serviceError as ChannelServiceError:
            switch serviceError {
            case .notConnected: return .notConnected
            case .channelNotFound, .invalidChannelIndex: return .invalidRecipient
            case .sessionError(let underlying): return .sessionError(underlying)
            case .secretHashingFailed, .saveFailed, .sendFailed,
                 .syncAlreadyInProgress, .circuitBreakerOpen:
                return .sendFailed
            }
        case let queueError as ChatSendQueueServiceError:
            switch queueError {
            case .notConnected: return .notConnected
            case .persistFailed: return .sendFailed
            }
        default:
            return .sendFailed
        }
    }

    private static func recipientName(for recipient: MessageRecipient) -> String {
        switch recipient {
        case .contact(let dto): dto.displayName
        case .channel(let dto): dto.displayName
        }
    }
}
