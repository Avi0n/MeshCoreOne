import Foundation

/// State of link preview loading for a message.
public enum PreviewLoadState: Sendable, Hashable {
    case idle
    case loading
    case loaded
    case noPreview
    case disabled
    case malwareWarning
}
