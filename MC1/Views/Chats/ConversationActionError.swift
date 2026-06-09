import Foundation

/// Failures surfaced by conversation delete actions, conforming to `LocalizedError` so the
/// message routes through the shared `.errorAlert`. Only the connection precondition lives here;
/// radio-command timeouts surface `MC1Services.withTimeout`'s `TimeoutError` directly.
enum ConversationActionError: LocalizedError {
    case notConnected

    var errorDescription: String? {
        switch self {
        case .notConnected: L10n.Chats.Chats.Error.notConnectedToDelete
        }
    }
}
