import Foundation
import MC1Services

/// Formats message routing path for display in message bubbles.
/// Returns the full, untruncated node list; the footer view fits it to a single
/// line at render time, collapsing nodes from the center to preserve the endpoints.
enum MessagePathFormatter {
  /// Formats the routing path for display
  /// - Parameter message: The message DTO containing path information
  /// - Returns: Formatted path string (e.g., "Direct", "Flood", or "A3,7F,42,B2,C1")
  static func format(_ message: MessageDTO) -> String {
    if message.isDirectRouted {
      return L10n.Chats.Chats.Message.Path.direct
    }

    // Destination marker: single 0xFF byte indicates direct message
    if let pathNodes = message.pathNodes,
       pathNodes.count == 1,
       pathNodes[0] == 0xFF {
      return L10n.Chats.Chats.Message.Path.direct
    }

    let nodes = message.pathNodesHex

    if nodes.isEmpty {
      return L10n.Chats.Chats.Message.Path.flood
    }

    return nodes.joined(separator: ",")
  }
}
