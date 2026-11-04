import Foundation
import SwiftData

@Model
final class PendingRadioErase {
  @Attribute(.unique) var radioID: UUID
  var deviceID: UUID
  var pairingID: UUID
  var radioName: String
  /// A legacy target has unknown purpose and must remain retryable.
  var isFactoryReset: Bool?

  init(_ target: PendingRadioEraseDTO) {
    radioID = target.radioID
    deviceID = target.deviceID
    pairingID = target.pairingID
    radioName = target.radioName
    isFactoryReset = target.isFactoryReset
  }
}

public struct PendingRadioEraseDTO: Sendable, Identifiable {
  public let radioID: UUID
  public let deviceID: UUID
  public let pairingID: UUID
  public let radioName: String
  let isFactoryReset: Bool?

  public var id: UUID {
    radioID
  }

  init(radioID: UUID, deviceID: UUID, pairingID: UUID, radioName: String, isFactoryReset: Bool?) {
    self.radioID = radioID
    self.deviceID = deviceID
    self.pairingID = pairingID
    self.radioName = radioName
    self.isFactoryReset = isFactoryReset
  }

  init(_ target: PendingRadioErase) {
    self.init(
      radioID: target.radioID,
      deviceID: target.deviceID,
      pairingID: target.pairingID,
      radioName: target.radioName,
      isFactoryReset: target.isFactoryReset
    )
  }
}
