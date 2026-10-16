import Foundation
import MC1Services

extension MeshCoreError {
  /// L10n-routed user-facing message for session errors. The package-level
  /// `errorDescription` provides an English fallback so MC1Services stays
  /// independent of the app target's L10n; views should prefer this property.
  var userFacingMessage: String {
    switch self {
    case .timeout:
      L10n.Localizable.Error.MeshCore.timeout
    case let .deviceError(code):
      Self.deviceErrorMessage(code: code)
    case let .parseError(detail):
      L10n.Localizable.Error.MeshCore.parseError(detail)
    case .notConnected:
      L10n.Localizable.Error.MeshCore.notConnected
    case let .commandFailed(_, reason):
      L10n.Localizable.Error.MeshCore.commandFailed(reason)
    case let .invalidResponse(expected, got):
      L10n.Localizable.Error.MeshCore.invalidResponse(expected, got)
    case .contactNotFound:
      L10n.Localizable.Error.MeshCore.contactNotFound
    case let .dataTooLarge(maxSize, actualSize):
      L10n.Localizable.Error.MeshCore.dataTooLarge(actualSize, maxSize)
    case let .signingFailed(reason):
      L10n.Localizable.Error.MeshCore.signingFailed(reason)
    case let .invalidInput(detail):
      L10n.Localizable.Error.MeshCore.invalidInput(detail)
    case let .unknown(detail):
      L10n.Localizable.Error.MeshCore.unknown(detail)
    case .bluetoothUnavailable:
      L10n.Localizable.Error.MeshCore.bluetoothUnavailable
    case .bluetoothUnauthorized:
      L10n.Localizable.Error.MeshCore.bluetoothUnauthorized
    case .bluetoothPoweredOff:
      L10n.Localizable.Error.MeshCore.bluetoothPoweredOff
    case let .connectionLost(underlying):
      if let underlying {
        L10n.Localizable.Error.MeshCore.connectionLost(underlying.userFacingMessage)
      } else {
        L10n.Localizable.Error.MeshCore.connectionLostNoDetail
      }
    case .sessionNotStarted:
      L10n.Localizable.Error.MeshCore.sessionNotStarted
    case .featureDisabled:
      L10n.Localizable.Error.MeshCore.featureDisabled
    }
  }

  /// Maps the firmware error sub-codes carried by `deviceError(code:)` to the
  /// shared `error.device.*` keys that `ProtocolError` also uses, so the two
  /// renderings of the same firmware codes cannot drift apart.
  private static func deviceErrorMessage(code: UInt8) -> String {
    guard let protocolError = ProtocolError(rawValue: code) else {
      return L10n.Localizable.Error.Device.unknown(Int(code))
    }
    return protocolError.userFacingMessage
  }
}
