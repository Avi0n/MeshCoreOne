import Foundation
import MeshCore

extension LPPDataPoint {
    /// Human-readable type name for the sensor channel
    public var typeName: String {
        type.name
    }

    /// Formatted value with appropriate unit suffix
    public var formattedValue: String {
        switch (type, value) {
        case (.voltage, .float(let v)): return "\(v.formatted(.number.precision(.fractionLength(3)))) V"
        case (.temperature, .float(let t)):
            let v = type.convertedValue(t)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.humidity, .float(let h)): return "\(h.formatted(.number.precision(.fractionLength(1))))%"
        case (.barometer, .float(let p)):
            let v = type.convertedValue(p)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.illuminance, .integer(let i)): return "\(i) lux"
        case (.percentage, .integer(let p)): return "\(p)%"
        case (.current, .float(let c)): return "\(c.formatted(.number.precision(.fractionLength(3)))) A"
        case (.power, .float(let p)): return "\(p.formatted(.number.precision(.fractionLength(1)))) W"
        case (.frequency, .float(let f)): return "\(f.formatted(.number.precision(.fractionLength(1)))) Hz"
        case (.altitude, .float(let a)):
            let v = type.convertedValue(a)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.distance, .float(let d)):
            let v = type.convertedValue(d)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.energy, .float(let e)): return "\(e.formatted(.number.precision(.fractionLength(3)))) kWh"
        case (.direction, .float(let d)): return "\(d.formatted(.number.precision(.fractionLength(0))))\u{00B0}"
        case (_, .digital(let b)): return b ? "On" : "Off"
        case (_, .integer(let i)): return "\(i)"
        case (_, .float(let f)): return f.formatted(.number.precision(.fractionLength(3)))
        case (_, .vector3(let x, let y, let z)):
            return "(\(x.formatted(.number.precision(.fractionLength(2)))), \(y.formatted(.number.precision(.fractionLength(2)))), \(z.formatted(.number.precision(.fractionLength(2)))))"
        case (_, .gps(let lat, let lon, let alt)):
            return "\(lat.formatted(.number.precision(.fractionLength(5)))), \(lon.formatted(.number.precision(.fractionLength(5)))) @ \(alt.formatted(.number.precision(.fractionLength(1))))m"
        case (_, .rgb(let r, let g, let b)):
            return "RGB(\(r), \(g), \(b))"
        case (_, .timestamp(let date)):
            return date.formatted(date: .abbreviated, time: .shortened)
        }
    }

    /// Estimated battery percentage based on voltage (3.0V=0%, 4.2V=100%)
    /// Returns nil for non-voltage types or non-float values
    public var batteryPercentage: Int? {
        guard type == .voltage, case .float(let voltage) = value else {
            return nil
        }

        let minVoltage: Double = 3.0
        let maxVoltage: Double = 4.2
        let percentage = (voltage - minVoltage) / (maxVoltage - minVoltage) * 100
        return max(0, min(100, Int(percentage.rounded())))
    }
}
