import Foundation
import MeshCore

/// Represents the auto-add behavior for discovered nodes.
public enum AutoAddMode: String, Codable, Sendable, CaseIterable {
    /// Review all nodes in Discover before adding.
    case manual
    /// Auto-add only the types enabled in settings.
    case selectedTypes
    /// Auto-add every discovered node.
    case all

    /// Bitmask covering all auto-add type bits.
    private static let typeBitsMask: UInt8 = AutoAddConfig.contactsBit
        | AutoAddConfig.repeatersBit
        | AutoAddConfig.roomServersBit
        | AutoAddConfig.sensorsBit

    /// Computes the auto-add mode from device settings.
    ///
    /// Protocol mapping:
    /// - `manualAddContacts=false` → `.all` (firmware auto-adds everything)
    /// - `manualAddContacts=true` + no type bits → `.manual` (user reviews all in Discover)
    /// - `manualAddContacts=true` + type bits set → `.selectedTypes` (firmware auto-adds selected types)
    ///
    /// - Parameters:
    ///   - manualAddContacts: Whether manual add mode is enabled on the device
    ///   - autoAddConfig: The bitmask of enabled auto-add types
    /// - Returns: The computed auto-add mode
    public static func mode(manualAddContacts: Bool, autoAddConfig: UInt8) -> AutoAddMode {
        if !manualAddContacts {
            return .all  // Firmware auto-adds everything
        } else if autoAddConfig & typeBitsMask == 0 {
            return .manual  // No type bits = review all in Discover
        } else {
            return .selectedTypes  // Type bits set = auto-add those types
        }
    }
}
