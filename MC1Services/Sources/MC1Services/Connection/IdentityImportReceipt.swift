import Foundation

struct IdentityImportReceipt: Equatable, Sendable {
  let receiptID = UUID()
  let originalDeviceID: UUID
  let originalRadioID: UUID
  let originalPublicKey: Data
  let sourceName: String
  let capturedConnectionMethod: ConnectionMethod
  var publicKey: Data?

  init(device: DeviceDTO, method: ConnectionMethod) {
    originalDeviceID = device.id
    originalRadioID = device.radioID
    originalPublicKey = device.publicKey
    sourceName = device.nodeName
    capturedConnectionMethod = method
  }

  func recovery(currentRequestID: UUID?) -> IdentityImportRecovery {
    IdentityImportRecovery(
      receiptID: receiptID, originalDeviceID: originalDeviceID, originalRadioID: originalRadioID,
      sourceName: sourceName, capturedConnectionMethod: capturedConnectionMethod, currentRequestID: currentRequestID
    )
  }

  var target: ConnectionRequest.Target {
    switch capturedConnectionMethod {
    case let .bluetooth(id, _): .bluetooth(id)
    case let .wifi(host, port, _): .wifi(host: host, port: port)
    }
  }

  var originalIdentity: ConnectionDeviceIdentity {
    ConnectionDeviceIdentity(deviceID: originalDeviceID, publicKey: originalPublicKey, radioID: originalRadioID)
  }
}
