import Foundation
import MeshCore

/// Events emitted by SettingsService when device settings change.
public enum SettingsEvent: Sendable {
    case deviceUpdated(MeshCore.SelfInfo)
    case autoAddConfigUpdated(MeshCore.AutoAddConfig)
    case clientRepeatUpdated(Bool)
    case pathHashModeUpdated(UInt8)
    case allowedRepeatFreqUpdated([MeshCore.FrequencyRange])
    case defaultFloodScopeUpdated(String?)
}
