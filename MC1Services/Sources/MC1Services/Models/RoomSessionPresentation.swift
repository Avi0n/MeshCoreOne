import Foundation

/// Contact presentation applied when a room session is reused or created.
/// Unread, notification, activity, telemetry, pin, and permission are not part of this value.
public struct RoomSessionPresentation: Sendable {
  public let radioID: UUID
  public let publicKey: Data
  public let name: String
  public let role: RemoteNodeRole
  public let latitude: Double
  public let longitude: Double

  public init(
    radioID: UUID,
    publicKey: Data,
    name: String,
    role: RemoteNodeRole,
    latitude: Double,
    longitude: Double
  ) {
    self.radioID = radioID
    self.publicKey = publicKey
    self.name = name
    self.role = role
    self.latitude = latitude
    self.longitude = longitude
  }
}
