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

  @Test
  func `Temperature conversion from Celsius to Fahrenheit`() {
    let celsius = 25.0
    let converted = LPPSensorType.temperature.convertedValue(celsius)
    if Locale.current.measurementSystem == .metric {
      #expect(converted == celsius)
    } else {
      #expect(abs(converted - 77.0) < 0.01)
    }
  }

  @Test
  func `Voltage is not locale-sensitive`() {
    let volts = 3.85
    let converted = LPPSensorType.voltage.convertedValue(volts)
    #expect(converted == volts)
    #expect(LPPSensorType.voltage.localizedUnitSymbol == "V")
  }

  @Test
  func `Altitude uses locale-appropriate unit symbol`() {
    let symbol = LPPSensorType.altitude.localizedUnitSymbol
    if Locale.current.measurementSystem == .metric {
      #expect(symbol == "m")
    } else {
      #expect(symbol == "ft")
    }
  }
}
