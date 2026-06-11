import SwiftUI

/// Centralized color definitions for the app.
///
/// Colors are organized by purpose:
/// - Identity palettes: Colors for identifying senders, contacts, and nodes
/// - UI elements: Colors for interface components like message bubbles
enum AppColors {

    // MARK: - Radio Status

    /// Colors for the BLE status indicator toolbar icon.
    enum Radio {
        static let repeatMode = Color(hex: 0xFF9500)
        static let connecting = Color(hex: 0x007AFF)
        static let ready = Color(hex: 0x34C759)
    }

    // MARK: - UI Elements

    /// Colors for message bubbles and related UI.
    enum Message {
        static let outgoingBubble = Color(hex: 0x2463EB)
        static let incomingBubble = Color(.systemGray5)

        /// Failed-bubble background. `highContrast: true` returns the
        /// system-adaptive `.systemRed`; `false` keeps the legacy translucent red.
        static func outgoingBubbleFailed(highContrast: Bool) -> Color {
            highContrast ? Color(.systemRed) : Color.red.opacity(0.8)
        }
    }
}
