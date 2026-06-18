import Foundation

// MARK: - MMA Parser

/// Specialized parser for MMA (Min/Max/Average) sensor data.
enum MMAParser {
    /// Parses MMA entries from binary protocol data.
    ///
    /// - Parameter data: Raw MMA data.
    /// - Returns: An array of ``MMAEntry`` structs.
    ///
    /// ### Binary Format
    /// `[channel:1][type:1][min:N][max:N][avg:N]`... where N is sensor data size.
    ///
    /// LPP sensor values use **Big-Endian** byte order.
    static func parse(_ data: Data) -> [MMAEntry] {
        var entries: [MMAEntry] = []
        var offset = 0

        while offset < data.count {
            guard offset + 2 <= data.count else { break }

            let channel = data[offset]
            let typeCode = data[offset + 1]
            offset += 2

            guard let sensorType = LPPSensorType(rawValue: typeCode) else { break }

            let valueSize = sensorType.dataSize
            guard offset + valueSize * 3 <= data.count else { break }

            let minData = data.subdata(in: offset..<(offset + valueSize))
            offset += valueSize
            let maxData = data.subdata(in: offset..<(offset + valueSize))
            offset += valueSize
            let avgData = data.subdata(in: offset..<(offset + valueSize))
            offset += valueSize

            let minValue = decodeToDouble(type: sensorType, data: minData)
            let maxValue = decodeToDouble(type: sensorType, data: maxData)
            let avgValue = decodeToDouble(type: sensorType, data: avgData)

            entries.append(MMAEntry(
                channel: channel,
                type: sensorType.name,
                min: minValue,
                max: maxValue,
                avg: avgValue
            ))
        }

        return entries
    }

    /// Decodes an LPP value to a double for MMA entries.
    ///
    /// - Parameters:
    ///   - type: The sensor type.
    ///   - data: Raw sensor data (Big-Endian).
    /// - Returns: Decoded floating point value.
    private static func decodeToDouble(type: LPPSensorType, data: Data) -> Double {
        switch type {
        case .digitalInput, .digitalOutput, .presence, .switchValue:
            return Double(data[0])
        case .percentage:
            return Double(data[0])
        case .humidity:
            return Double(data[0]) * 0.5
        case .temperature:
            return Double(readInt16BE(data)) / 10.0
        case .barometer:
            return Double(readUInt16BE(data)) / 10.0
        case .voltage:
            return Double(readUInt16BE(data)) / 100.0
        case .current:
            return Double(readUInt16BE(data)) / 1000.0
        case .illuminance, .concentration, .power, .direction:
            return Double(readUInt16BE(data))
        case .altitude:
            return Double(readInt16BE(data))
        case .load:
            return Double(readInt24BE(data)) / 1000.0
        case .analogInput, .analogOutput:
            return Double(readInt16BE(data)) / 100.0
        case .genericSensor:
            return Double(readUInt32BE(data))
        case .frequency:
            return Double(readUInt32BE(data))
        case .distance, .energy:
            return Double(readUInt32BE(data)) / 1000.0
        case .unixTime:
            return Double(readUInt32BE(data))
        case .accelerometer, .gyrometer, .colour, .gps:
            // Complex types - return first component only for MMA
            return Double(readInt16BE(data)) / (type == .accelerometer ? 1000.0 : 100.0)
        }
    }

    /// Reads a 16-bit signed integer (Big-Endian).
    private static func readInt16BE(_ data: Data, offset: Int = 0) -> Int16 {
        guard offset + 2 <= data.count else { return 0 }
        return Int16(data[offset]) << 8 | Int16(data[offset + 1])
    }

    /// Reads a 24-bit signed integer (Big-Endian).
    private static func readInt24BE(_ data: Data, offset: Int = 0) -> Int32 {
        guard offset + 3 <= data.count else { return 0 }
        var value: Int32 = Int32(data[offset]) << 16
                         | Int32(data[offset + 1]) << 8
                         | Int32(data[offset + 2])
        // Sign extend if negative (bit 23 is set)
        if value & 0x800000 != 0 {
            value |= Int32(bitPattern: 0xFF000000)
        }
        return value
    }

    /// Reads a 16-bit unsigned integer (Big-Endian).
    private static func readUInt16BE(_ data: Data, offset: Int = 0) -> UInt16 {
        guard offset + 2 <= data.count else { return 0 }
        return UInt16(data[offset]) << 8 | UInt16(data[offset + 1])
    }

    /// Reads a 32-bit unsigned integer (Big-Endian).
    private static func readUInt32BE(_ data: Data, offset: Int = 0) -> UInt32 {
        guard offset + 4 <= data.count else { return 0 }
        return UInt32(data[offset]) << 24 | UInt32(data[offset + 1]) << 16
             | UInt32(data[offset + 2]) << 8 | UInt32(data[offset + 3])
    }
}
