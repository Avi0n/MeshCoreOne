import Foundation
import MeshCore

/// Errors that can occur during message operations.
public enum MessageServiceError: Error, Sendable {
  /// Not connected to a device
  case notConnected
  /// Contact not found in database
  case contactNotFound
  /// Channel not found in database
  case channelNotFound
  /// Message send operation failed
  case sendFailed(String)
  /// Attempted to send message to invalid recipient (e.g., repeater)
  case invalidRecipient
  /// Message text exceeds maximum allowed length
  case messageTooLong
  /// Underlying MeshCore session error
  case sessionError(MeshCoreError)
}

extension MessageServiceError: LocalizedError {
  public var errorDescription: String? {
    switch self {
    case .notConnected: "Not connected to device."
    case .contactNotFound: "Contact not found."
    case .channelNotFound: "Channel not found."
    case let .sendFailed(msg): "Send failed: \(msg)"
    case .invalidRecipient: "Cannot send messages to this recipient."
    case .messageTooLong: "Message exceeds the maximum allowed length."
    case let .sessionError(e): e.localizedDescription
    }
  }
}
