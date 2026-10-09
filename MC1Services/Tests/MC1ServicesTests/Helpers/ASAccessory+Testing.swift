#if canImport(UIKit)
  import AccessorySetupKit
  import Foundation

  extension ASAccessory {
    /// Test fixture. ASK does not expose a public labeled initializer; KVC fills
    /// the readonly identity fields used by `AccessorySetupPairingService`.
    convenience init(bluetoothIdentifier: UUID, displayName: String) {
      self.init()
      setValue(bluetoothIdentifier, forKey: "bluetoothIdentifier")
      setValue(displayName, forKey: "displayName")
    }
  }
#endif
