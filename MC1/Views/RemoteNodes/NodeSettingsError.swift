import Foundation

/// Shared error type for the repeater and room settings screens.
enum NodeSettingsError: LocalizedError {
    case noService

    var errorDescription: String? {
        switch self {
        case .noService: return L10n.RemoteNodes.RemoteNodes.Settings.noService
        }
    }
}
