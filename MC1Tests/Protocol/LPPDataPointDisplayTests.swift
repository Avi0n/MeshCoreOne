import Foundation
@testable import MC1Services
import Testing

@Suite("LPP Data Point Display Tests")
struct LPPDataPointDisplayTests {
  // MARK: - Battery Percentage Tests

  @Test
  func `Battery percentage at minimum voltage (3.0V) returns 0%`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .float(3.0))

    #expect(dataPoint.batteryPercentage == 0)
  }

  @Test
  func `Battery percentage at maximum voltage (4.2V) returns 100%`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .float(4.2))

    #expect(dataPoint.batteryPercentage == 100)
  }

  @Test
  func `Battery percentage at midpoint voltage (3.6V) returns approximately 50%`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .float(3.6))

    #expect(dataPoint.batteryPercentage == 50)
  }

  @Test
  func `Battery percentage at 3.9V returns 75%`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .float(3.9))

    #expect(dataPoint.batteryPercentage == 75)
  }

  @Test
  func `Battery percentage below minimum voltage clamps to 0%`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .float(2.5))

    #expect(dataPoint.batteryPercentage == 0)
  }

  @Test
  func `Battery percentage above maximum voltage clamps to 100%`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .float(5.0))

    #expect(dataPoint.batteryPercentage == 100)
  }

  @Test
  func `Battery percentage for non-voltage type returns nil`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .temperature, value: .float(25.0))

    #expect(dataPoint.batteryPercentage == nil)
  }

  @Test
  func `Battery percentage for integer value returns nil`() {
    // Voltage should be float, but test edge case where it's not
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .integer(4))

    #expect(dataPoint.batteryPercentage == nil)
  }

  @Test
  func `Battery percentage for percentage type returns nil`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .percentage, value: .integer(75))

    #expect(dataPoint.batteryPercentage == nil)
  }

  // MARK: - Formatted Value Tests

  @Test
  func `Voltage formatted value includes V suffix`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .voltage, value: .float(3.85))

    #expect(dataPoint.formattedValue == "\(3.85.formatted(.number.precision(.fractionLength(3)))) V")
  }

  @Test
  func `Temperature formatted value uses locale-appropriate unit`() {
    let dataPoint = LPPDataPoint(channel: 0, type: .temperature, value: .float(25.5))
    let sensorType = LPPSensorType.temperature
    let converted = sensorType.convertedValue(25.5)
    let precision = sensorType.convertedFractionLength
    let expected = "\(converted.formatted(.number.precision(.fractionLength(precision)))) \(sensorType.localizedUnitSymbol)"

    #expect(dataPoint.formattedValue == expected)
  }

  // MARK: - Locale Conversion Tests

  @Test(arguments: [
    (identifier: "en_US", expected: 77.0, symbol: "°F"),
    (identifier: "en_GB", expected: 25.0, symbol: "°C"),
    (identifier: "en_LR", expected: 25.0, symbol: "°C"),
    (identifier: "de_DE", expected: 25.0, symbol: "°C"),
  ])
  func `temperature uses the locale temperature unit`(
    identifier: String,
    expected: Double,
    symbol: String
  ) {
    let locale = Locale(identifier: identifier)
    let converted = LPPSensorType.temperature.convertedValue(25.0, locale: locale)
    #expect(abs(converted - expected) < 0.01)
    #expect(LPPSensorType.temperature.localizedUnitSymbol(for: locale) == symbol)
  }

  @Test(arguments: [
    (identifier: "en_US", text: "29.92 inHg"),
    (identifier: "en_GB", text: "1,013.2 mbar"),
    (identifier: "en_LR", text: "1,013.2 hPa"),
    (identifier: "de_DE", text: "1.013,2 hPa"),
    (identifier: "ru_RU", text: "760 mmHg"),
  ])
  func `barometer reading uses the locale pressure unit`(identifier: String, text: String) {
    let locale = Locale(identifier: identifier)
    let sample = 1013.2
    let value = LPPSensorType.barometer.convertedValue(sample, locale: locale)
    let digits = LPPSensorType.barometer.convertedFractionLength(for: locale)
    let symbol = LPPSensorType.barometer.localizedUnitSymbol(for: locale)
    let formatted = value.formatted(.number.precision(.fractionLength(digits)).locale(locale))
    #expect(text == "\(formatted) \(symbol)")
  }

  @Test(arguments: [
    (identifier: "en_US", symbol: "ft"),
    (identifier: "en_GB", symbol: "ft"),
    (identifier: "en_LR", symbol: "m"),
    (identifier: "de_DE", symbol: "m"),
  ])
  func `altitude uses the locale length unit`(identifier: String, symbol: String) {
    let locale = Locale(identifier: identifier)
    #expect(LPPSensorType.altitude.localizedUnitSymbol(for: locale) == symbol)
  }

  @Test
  func `Voltage is not locale-sensitive`() {
    let volts = 3.85
    let converted = LPPSensorType.voltage.convertedValue(volts)
    #expect(converted == volts)
    #expect(LPPSensorType.voltage.localizedUnitSymbol == "V")
  }
}
