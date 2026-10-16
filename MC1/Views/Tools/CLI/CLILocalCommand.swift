import Foundation

/// A parsed local-session radio command. Typed reads collapse into one `getKey`
/// case because each dispatches on the key alone; a custom-var read carries the
/// raw key (`getCustomVar`) or dumps every var (`getCustomVars`). Writes stay
/// individual cases because each carries a differently-typed, validated payload.
enum CLILocalCommand: Equatable {
  case clock
  case clockSync
  case ver
  case board
  case advert(flood: Bool)
  case reboot
  case getKey(CLILocalKey)
  case setName(String)
  case setLatitude(Double)
  case setLongitude(Double)
  case setTxPower(Int8)
  case setRadio(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: UInt8, codingRate: UInt8)
  case setFrequency(Double)
  case setMultiAcks(UInt8)
  case setPathHashMode(UInt8)
  case getCustomVars
  case getCustomVar(String)
  case setCustomVar(key: String, value: String)

  /// Commands that drop the link or move the node off-mesh require a terminal
  /// confirm step before executing.
  var requiresConfirmation: Bool {
    switch self {
    case .reboot, .setRadio, .setFrequency:
      true
    default:
      false
    }
  }

  /// The command text shown in the confirm prompt.
  var displayName: String {
    switch self {
    case .reboot: "reboot"
    case .setRadio: "set radio"
    case .setFrequency: "set freq"
    default: ""
    }
  }
}

/// The `get`/`set` key vocabulary for the local session, mirroring the firmware
/// CLI's dotted names verbatim.
enum CLILocalKey: String, Equatable, CaseIterable {
  case name
  case lat
  case lon
  case tx
  case radio
  case freq
  case publicKey = "public.key"
  case multiAcks = "multi.acks"
  case pathHashMode = "path.hash.mode"
  case bat
}
