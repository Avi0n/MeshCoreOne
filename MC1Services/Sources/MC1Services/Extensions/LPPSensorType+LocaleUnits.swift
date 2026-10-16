import Foundation
import MeshCore

public extension LPPSensorType {
  /// Converts an SI telemetry value to the user's locale-preferred unit.
  /// Non-locale-sensitive types return the input unchanged.
  func convertedValue(_ siValue: Double) -> Double {
    guard let target = preferredUnit, let si = siDimension else { return siValue }
    return Measurement(value: siValue, unit: si).converted(to: target).value
  }

  /// The locale-appropriate unit symbol (e.g. "°F" on US, "°C" on metric).
  /// Falls back to the SI `unit` property for non-locale-sensitive types.
  var localizedUnitSymbol: String {
    guard let target = preferredUnit else { return unit }
    return target.symbol
  }

  /// Whether this type's value will be converted for the current locale.
  var isConverted: Bool {
    preferredUnit != nil
  }

  /// The fraction length appropriate for the converted value.
  var convertedFractionLength: Int {
    guard isConverted else {
      switch self {
      case .temperature, .barometer, .altitude: return 1
      case .distance: return 3
      default: return 1
      }
    }
    switch self {
    case .temperature: return 1
    case .barometer: return 2 // 29.92 inHg
    case .altitude: return 0 // whole feet
    case .distance: return 1
    default: return 1
    }
  }

  // MARK: - Private

  /// The locale-preferred Dimension for this sensor type, or nil if metric (no conversion needed).
  private var preferredUnit: Dimension? {
    guard siDimension != nil,
          Locale.current.measurementSystem != .metric else { return nil }
    switch self {
    case .temperature: return UnitTemperature.fahrenheit
    case .barometer: return UnitPressure.inchesOfMercury
    case .altitude: return UnitLength.feet
    case .distance: return UnitLength.feet
    default: return nil
    }
  }

  /// Maps locale-sensitive sensor types to their Foundation SI Dimension.
  private var siDimension: Dimension? {
    switch self {
    case .temperature: UnitTemperature.celsius
    case .barometer: UnitPressure.hectopascals
    case .altitude: UnitLength.meters
    case .distance: UnitLength.meters
    default: nil
    }
  }
}
