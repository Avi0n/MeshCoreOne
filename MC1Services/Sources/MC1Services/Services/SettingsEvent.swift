import Foundation
import MeshCore

/// Scoped setting changes and explicit full device observations.
/// Write completions carry only their changed fields so unrelated values stay current.
public enum SettingsEvent: Sendable {
  public enum CatalogStamp: Sendable, Equatable {
    /// Keep the stored catalog id when the new radio parameters still match it.
    case reconcile
    /// Store this catalog id. Used only after a preset's required path-hash write succeeds.
    case set(String)
  }

  public struct RadioChange: Sendable, Equatable {
    public var frequencyKHz: UInt32
    public var bandwidthHz: UInt32
    public var spreadingFactor: UInt8
    public var codingRate: UInt8
    public var catalogStamp: CatalogStamp

    public init(
      frequencyKHz: UInt32,
      bandwidthHz: UInt32,
      spreadingFactor: UInt8,
      codingRate: UInt8,
      catalogStamp: CatalogStamp
    ) {
      self.frequencyKHz = frequencyKHz
      self.bandwidthHz = bandwidthHz
      self.spreadingFactor = spreadingFactor
      self.codingRate = codingRate
      self.catalogStamp = catalogStamp
    }
  }

  case nameUpdated(String)
  case coordinatesUpdated(latitude: Double, longitude: Double)
  /// Full self info from an explicit refresh. Scoped writes keep their own events.
  case deviceObserved(MeshCore.SelfInfo)
  case radioUpdated(RadioChange)
  case txPowerUpdated(Int8)
  case otherParamsUpdated(OtherParamsIntent)
  case autoAddConfigUpdated(MeshCore.AutoAddConfig)
  case clientRepeatUpdated(Bool)
  case pathHashModeUpdated(UInt8)
  case allowedRepeatFreqUpdated([MeshCore.FrequencyRange])
  case defaultFloodScopeUpdated(String?)
}
