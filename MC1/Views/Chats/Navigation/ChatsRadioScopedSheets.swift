import Foundation

/// Drops a copied room-auth sheet when the radio that produced it is gone.
enum ChatsRadioScopedSheets {
  static func shouldKeepRoomAuth(
    sessionRadioID: UUID,
    currentRadioID: UUID?,
    hasConnectedDevice: Bool
  ) -> Bool {
    hasConnectedDevice && currentRadioID == sessionRadioID
  }
}
