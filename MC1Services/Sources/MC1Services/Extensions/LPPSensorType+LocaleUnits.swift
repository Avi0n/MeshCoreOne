import Foundation
import MeshCore

public extension LPPSensorType {
  /// Converts an SI telemetry value to the user's locale-preferred unit.
  /// Non-locale-sensitive types return the input unchanged.
  func convertedValue(_ siValue: Double) -> Double {
    convertedValue(siValue, locale: .current)
  }

  func convertedValue(_ siValue: Double, locale: Locale) -> Double {
    guard let target = preferredUnit(for: locale), let si = siDimension else { return siValue }
    return Measurement(value: siValue, unit: si).converted(to: target).value
  }

  /// The locale-appropriate unit symbol (e.g. "°F" on US, "°C" on metric).
  /// Falls back to the SI `unit` property for non-locale-sensitive types.
  var localizedUnitSymbol: String {
    localizedUnitSymbol(for: .current)
  }

  func localizedUnitSymbol(for locale: Locale) -> String {
    guard let target = preferredUnit(for: locale) else { return unit }
    return target.symbol
  }

  /// Whether this type's value will be converted for the current locale.
  var isConverted: Bool {
    isConverted(for: .current)
  }

  func isConverted(for locale: Locale) -> Bool {
    guard let target = preferredUnit(for: locale), let si = siDimension else { return false }
    return target != si
  }

  /// The fraction length appropriate for the converted value.
  var convertedFractionLength: Int {
    convertedFractionLength(for: .current)
  }

  func convertedFractionLength(for locale: Locale) -> Int {
    guard isConverted(for: locale) else {
      switch self {
      case .temperature, .barometer, .altitude: return 1
      case .distance: return 3
      default: return 1
      }
    }
    switch self {
    case .temperature: return 1
    case .barometer: return barometerFractionLength(for: locale)
    case .altitude: return 0
    case .distance: return 1
    default: return 1
    }
  }

  // MARK: - Private

  /// The Foundation locale-preferred unit, or nil for non-locale-sensitive types.
  private func preferredUnit(for locale: Locale) -> Dimension? {
    switch self {
    case .temperature: UnitTemperature(forLocale: locale)
    case .barometer: UnitPressure(forLocale: locale, usage: .barometric)
    case .altitude, .distance: UnitLength(forLocale: locale)
    default: nil
    }
  }

  /// Millibars match the hectopascal number and still compare unequal, so the digit count follows the unit.
  private func barometerFractionLength(for locale: Locale) -> Int {
    guard let target = preferredUnit(for: locale) else { return 1 }
    if target == UnitPressure.inchesOfMercury { return 2 }
    if target == UnitPressure.millimetersOfMercury { return 0 }
    return 1
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
