import Foundation

/// Reasons for disconnecting from a device (for debugging)
public enum DisconnectReason: String, Sendable {
    case userInitiated = "user initiated disconnect"
    case statusMenuDisconnectTap = "status menu disconnect tapped"
    case switchingDevice = "switching to new device"
    case factoryReset = "device factory reset"
    case wifiAddressChange = "WiFi address changed"
    case resyncFailed = "resync failed after 3 attempts"
    case forgetDevice = "user forgot device"
    case deviceRemovedFromSettings = "device removed from iOS Settings"
    case pairingFailed = "device pairing failed"
    case wifiReconnectPrep = "preparing for WiFi reconnect"
}
