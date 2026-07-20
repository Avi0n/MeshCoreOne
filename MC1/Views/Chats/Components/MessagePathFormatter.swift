import Foundation
import MC1Services

/// Formats a message's routing path into a display-ready string for the bubble footer.
/// A path longer than the cap collapses its middle to a tight ellipsis (`head…tail`) so the
/// endpoints stay visible. Truncating here (rather than in the footer view) keeps the accessibility
/// label — which reads the same string — matched to what's on screen, and avoids a width-measuring
/// layout pass while scrolling.
enum MessagePathFormatter {
  /// Maximum hop IDs shown before the middle collapses to an ellipsis.
  static let maxNodes = 4

  /// Formats the routing path for display.
  /// - Parameter message: The message DTO containing path information
  /// - Returns: `"Direct"`, `"Flood"`, the hop IDs (`"A3,7F,42"`), or a middle-collapsed
  ///   list (`"A3,7F…B2,C1"`) when longer than `maxNodes`.
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

    return truncated(nodes)
  }

  /// Joins the hop IDs, middle-collapsing to the first two and last two around a tight ellipsis
  /// once the path exceeds `maxNodes`.
  private static func truncated(_ nodes: [String]) -> String {
    guard nodes.count > maxNodes else { return nodes.joined(separator: ",") }
    let head = nodes.prefix(2).joined(separator: ",")
    let tail = nodes.suffix(2).joined(separator: ",")
    return "\(head)…\(tail)"
  }
}
