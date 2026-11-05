import Foundation

/// Facts acquired about a radio independently of the address used to reach it.
struct ConnectionDeviceIdentity: Sendable, Equatable {
  var deviceID: UUID?
  var publicKey: Data?
  var radioID: UUID?

  init(deviceID: UUID? = nil, publicKey: Data? = nil, radioID: UUID? = nil) {
    self.deviceID = deviceID
    self.publicKey = publicKey
    self.radioID = radioID
  }

  init(_ device: DeviceDTO) {
    deviceID = device.id
    publicKey = device.publicKey
    radioID = device.radioID
  }

  func matches(_ other: Self) -> Bool {
    if let radioID, radioID == other.radioID { return true }
    if let publicKey, !publicKey.isEmpty, publicKey == other.publicKey { return true }
    if let deviceID, deviceID == other.deviceID { return true }
    return false
  }
}
