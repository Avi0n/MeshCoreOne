import Foundation
import MeshCore

public extension LPPDataPoint {
  /// Human-readable type name for the sensor channel
  var typeName: String {
    type.name
  }

  /// Formatted value with appropriate unit suffix
  var formattedValue: String {
    switch (type, value) {
    case let (.voltage, .float(v)): return "\(v.formatted(.number.precision(.fractionLength(3)))) V"
    case let (.temperature, .float(t)):
      let v = type.convertedValue(t)
      return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
    case let (.humidity, .float(h)): return "\(h.formatted(.number.precision(.fractionLength(1))))%"
    case let (.barometer, .float(p)):
      let v = type.convertedValue(p)
      return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
    case let (.illuminance, .integer(i)): return "\(i) lux"
    case let (.percentage, .integer(p)): return "\(p)%"
    case let (.current, .float(c)): return "\(c.formatted(.number.precision(.fractionLength(3)))) A"
    case let (.power, .float(p)): return "\(p.formatted(.number.precision(.fractionLength(1)))) W"
    case let (.frequency, .float(f)): return "\(f.formatted(.number.precision(.fractionLength(1)))) Hz"
    case let (.altitude, .float(a)):
      let v = type.convertedValue(a)
      return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
    case let (.distance, .float(d)):
      let v = type.convertedValue(d)
      return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
    case let (.energy, .float(e)): return "\(e.formatted(.number.precision(.fractionLength(3)))) kWh"
    case let (.direction, .float(d)): return "\(d.formatted(.number.precision(.fractionLength(0))))\u{00B0}"
    case let (_, .digital(b)): return b ? "On" : "Off"
    case let (_, .integer(i)): return "\(i)"
    case let (_, .float(f)): return f.formatted(.number.precision(.fractionLength(3)))
    case let (_, .vector3(x, y, z)):
      return "(\(x.formatted(.number.precision(.fractionLength(2)))), \(y.formatted(.number.precision(.fractionLength(2)))), \(z.formatted(.number.precision(.fractionLength(2)))))"
    case let (_, .gps(lat, lon, alt)):
      return "\(lat.formatted(.number.precision(.fractionLength(5)))), \(lon.formatted(.number.precision(.fractionLength(5)))) @ \(alt.formatted(.number.precision(.fractionLength(1))))m"
    case let (_, .rgb(r, g, b)):
      return "RGB(\(r), \(g), \(b))"
    case let (_, .timestamp(date)):
      return date.formatted(date: .abbreviated, time: .shortened)
    }
  }

  /// Estimated battery percentage based on voltage (3.0V=0%, 4.2V=100%)
  /// Returns nil for non-voltage types or non-float values
  var batteryPercentage: Int? {
    guard type == .voltage, case let .float(voltage) = value else {
      return nil
    }

    let minVoltage = 3.0
    let maxVoltage = 4.2
    let percentage = (voltage - minVoltage) / (maxVoltage - minVoltage) * 100
    return max(0, min(100, Int(percentage.rounded())))
  }
}
