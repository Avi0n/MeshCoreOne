import Foundation

/// Direction-tagged colour slot for chat-message text. The view layer
/// resolves `.outgoing` to white text on the filled bubble and `.incoming`
/// to the system primary colour at render time. Keeping the slot here
/// rather than a concrete `SwiftUI.Color` allows `MessageItem` and the
/// rest of the chat content model to live in MC1Services without a
/// SwiftUI dependency.
public enum BaseColorSlot: Sendable, Hashable {
  case outgoing
  case incoming
}
