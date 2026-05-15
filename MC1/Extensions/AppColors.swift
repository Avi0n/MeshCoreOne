import SwiftUI

/// Centralized color definitions for the app.
///
/// Colors are organized by purpose:
/// - Identity palettes: Colors for identifying senders, contacts, and nodes
/// - UI elements: Colors for interface components like message bubbles
enum AppColors {

    // MARK: - Identity Palettes

    /// Colors for identifying users by name.
    ///
    /// Used for sender names in messages, contact avatars, and mentions.
    /// The standard palette uses muted earth tones designed for visual harmony.
    /// For users with increased contrast enabled, use `paletteHighContrast` which
    /// meets WCAG AA 4.5:1 contrast ratio against white backgrounds.
    enum NameColor {
        /// Standard palette with distinct hues spread across the color wheel.
        static let palette: [Color] = [
            Color(hex: 0xc97d5d), // coral (orange-red)
            Color(hex: 0xb59845), // amber (yellow-gold)
            Color(hex: 0x6a9e6a), // sage (green)
            Color(hex: 0x4d9999), // teal (cyan)
            Color(hex: 0x5578a8), // steel blue
            Color(hex: 0x7868ab), // indigo (blue-violet)
            Color(hex: 0xa06699), // orchid (magenta)
            Color(hex: 0xb56078), // rose (pink-red)
            Color(hex: 0xa67858), // sienna (brown)
            Color(hex: 0x8fa84d) // lime (yellow-green)
        ]

        /// High-contrast palette meeting WCAG AA 4.5:1 contrast ratio.
        /// Used when `ColorSchemeContrast.increased` is enabled.
        static let paletteHighContrast: [Color] = [
            Color(hex: 0x975e46), // coral (darkened)
            Color(hex: 0x887234), // amber (darkened)
            Color(hex: 0x4f7650), // sage (darkened)
            Color(hex: 0x3a7373), // teal (darkened)
            Color(hex: 0x405a7e), // steel blue (darkened)
            Color(hex: 0x5a4e80), // indigo (darkened)
            Color(hex: 0x784d73), // orchid (darkened)
            Color(hex: 0x88485a), // rose (darkened)
            Color(hex: 0x7c5a42), // sienna (darkened)
            Color(hex: 0x6b7e3a) // lime (darkened)
        ]

        /// Returns a color for the given sender name.
        ///
        /// Uses XOR hashing to deterministically map names to colors.
        /// The same name always returns the same color.
        ///
        /// - Parameters:
        ///   - name: The sender's display name.
        ///   - highContrast: When true, uses high-contrast palette for accessibility.
        /// - Returns: A color from the appropriate palette.
        static func color(for name: String, highContrast: Bool = false) -> Color {
            let colors = highContrast ? paletteHighContrast : palette
            let hash = name.utf8.reduce(0) { $0 ^ Int($1) }
            return colors[abs(hash) % colors.count]
        }
    }

    /// Colors for room server node avatars.
    enum RoomServerAvatar {
        static let palette: [Color] = [
            Color(hex: 0xff8800), // orange
            Color(hex: 0xff6600), // orange (darker)
            Color(hex: 0xffaa00), // orange (lighter)
            Color(hex: 0xcc5500) // orange (dark)
        ]

        /// Returns a color for the given room server.
        ///
        /// If publicKey is empty, returns the first palette color.
        static func color(for publicKey: Data) -> Color {
            let hash = publicKey.prefix(4).reduce(0) { $0 ^ Int($1) }
            return palette[abs(hash) % palette.count]
        }
    }

    /// Colors for repeater node avatars.
    enum RepeaterAvatar {
        static let palette: [Color] = [
            Color(hex: 0x00aaff), // cyan
            Color(hex: 0x0088cc) // medium blue
        ]

        /// Returns a color for the repeater at the given index.
        static func color(at index: Int) -> Color {
            palette[index % palette.count]
        }
    }

    /// Color for channel avatars.
    enum ChannelAvatar {
        static let color = Color(hex: 0x336688) // slate blue
    }

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
        /// system-adaptive `.systemRed`; `false` keeps the legacy translucent
        /// red. Matches the `NameColor.color(for:highContrast:)` shape.
        static func outgoingBubbleFailed(highContrast: Bool) -> Color {
            highContrast ? Color(.systemRed) : Color.red.opacity(0.8)
        }
    }
}
