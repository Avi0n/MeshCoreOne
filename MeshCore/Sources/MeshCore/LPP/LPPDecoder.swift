import Foundation

// MARK: - LPP Sensor Types

/// Represents Cayenne Low Power Payload (LPP) sensor types.
///
/// LPP is a compact binary format for transmitting sensor data over low-bandwidth
/// networks like LoRa. Each sensor type has a defined data size and encoding.
///
/// For the full specification, see:
/// [Cayenne LPP Documentation](https://developers.mydevices.com/cayenne/docs/lora/#lora-cayenne-low-power-payload)
public enum LPPSensorType: UInt8, Sendable, CaseIterable {
    /// Digital input (1 byte).
    case digitalInput = 0
    /// Digital output (1 byte).
    case digitalOutput = 1
    /// Analog input (2 bytes, 0.01 resolution).
    case analogInput = 2
    /// Analog output (2 bytes, 0.01 resolution).
    case analogOutput = 3
    /// Generic sensor (4 bytes).
    case genericSensor = 100
    /// Illuminance (2 bytes, 1 lux resolution).
    case illuminance = 101
    /// Presence (1 byte).
    case presence = 102
    /// Temperature (2 bytes, 0.1°C resolution).
    case temperature = 103
    /// Humidity (1 byte, 0.5% resolution).
    case humidity = 104
    /// Accelerometer (6 bytes, 0.001G resolution).
    case accelerometer = 113
    /// Barometer (2 bytes, 0.1 hPa resolution).
    case barometer = 115
    /// Voltage (2 bytes, 0.01V resolution).
    case voltage = 116
    /// Current (2 bytes, 0.001A resolution).
    case current = 117
    /// Frequency (4 bytes, 1 Hz resolution).
    case frequency = 118
    /// Percentage (1 byte, 1% resolution).
    case percentage = 120
    /// Altitude (2 bytes, 1m resolution).
    case altitude = 121
    /// Load (3 bytes, 0.001kg resolution, signed).
    case load = 122
    /// Concentration (2 bytes, 1 ppm resolution).
    case concentration = 125
    /// Power (2 bytes, 1W resolution).
    case power = 128
    /// Distance (4 bytes, 0.001m resolution).
    case distance = 130
    /// Energy (4 bytes, 0.001kWh resolution).
    case energy = 131
    /// Direction (2 bytes, 1° resolution).
    case direction = 132
    /// Unix Time (4 bytes).
    case unixTime = 133
    /// Gyrometer (6 bytes, 0.01°/s resolution).
    case gyrometer = 134
    /// Colour (3 bytes, RGB).
    case colour = 135
    /// GPS (9 bytes, 0.0001° lat/lon, 0.01m alt).
    case gps = 136
    /// Switch (1 byte).
    case switchValue = 142

    /// Returns the size in bytes for this sensor type's data payload.
    public var dataSize: Int {
        switch self {
        // 1-byte types
        case .digitalInput, .digitalOutput, .presence, .humidity, .percentage, .switchValue:
            1
        // 2-byte types
        case .analogInput, .analogOutput, .illuminance, .temperature, .barometer,
             .voltage, .current, .altitude, .concentration, .power, .direction:
            2
        // 3-byte types
        case .colour, .load:
            3
        // 4-byte types
        case .genericSensor, .frequency, .distance, .energy, .unixTime:
            4
        // 6-byte types (3 x 2-byte values)
        case .accelerometer, .gyrometer:
            6
        // 9-byte types (3 x 3-byte values)
        case .gps:
            9
        }
    }

    /// Returns the human-readable name for the sensor type.
    public var name: String {
        switch self {
        case .digitalInput: "Digital Input"
        case .digitalOutput: "Digital Output"
        case .analogInput: "Analog Input"
        case .analogOutput: "Analog Output"
        case .genericSensor: "Sensor"
        case .illuminance: "Illuminance"
        case .presence: "Presence"
        case .temperature: "Temperature"
        case .humidity: "Humidity"
        case .accelerometer: "Accelerometer"
        case .barometer: "Pressure"
        case .voltage: "Voltage"
        case .current: "Current"
        case .frequency: "Frequency"
        case .percentage: "Percentage"
        case .altitude: "Altitude"
        case .load: "Load"
        case .concentration: "Concentration"
        case .power: "Power"
        case .distance: "Distance"
        case .energy: "Energy"
        case .direction: "Direction"
        case .unixTime: "Time"
        case .gyrometer: "Gyrometer"
        case .colour: "Colour"
        case .gps: "GPS"
        case .switchValue: "Switch"
        }
    }

    /// Returns the standard unit symbol for the sensor type.
    public var unit: String {
        switch self {
        case .voltage: "V"
        case .temperature: "\u{00B0}C"
        case .humidity, .percentage: "%"
        case .barometer: "hPa"
        case .illuminance: "lux"
        case .current: "A"
        case .power: "W"
        case .frequency: "Hz"
        case .altitude, .distance: "m"
        case .energy: "kWh"
        case .direction: "\u{00B0}"
        case .load: "kg"
        case .concentration: "ppm"
        default: ""
        }
    }

    /// Looks up a sensor type by its human-readable name.
    public init?(name: String) {
        guard let match = Self.allCases.first(where: { $0.name == name }) else { return nil }
        self = match
    }
}

// MARK: - LPP Values

/// Represents a decoded LPP sensor value.
///
/// `LPPValue` contains the decoded data from a sensor reading. The specific
/// case indicates the value type, which depends on the sensor.
public enum LPPValue: Sendable, Equatable, Hashable {
    /// Boolean value (digital input/output, presence, switch).
    case digital(Bool)

    /// Integer value (illuminance in lux, percentage, direction in degrees).
    case integer(Int)

    /// Floating-point value with unit context.
    case float(Double)

    /// 3D vector (accelerometer in g, gyrometer in degrees/s).
    case vector3(x: Double, y: Double, z: Double)

    /// GPS coordinates.
    case gps(latitude: Double, longitude: Double, altitude: Double)

    /// RGB colour.
    case rgb(red: UInt8, green: UInt8, blue: UInt8)

    /// Unix timestamp.
    case timestamp(Date)
}

// MARK: - LPP Data Point

/// Represents a single decoded LPP data point.
public struct LPPDataPoint: Sendable, Equatable, Hashable {
    /// The channel identifier (application-specific).
    public let channel: UInt8

    /// The sensor type.
    public let type: LPPSensorType

    /// The decoded value.
    public let value: LPPValue

    /// Creates a new LPP data point.
    ///
    /// - Parameters:
    ///   - channel: Channel identifier.
    ///   - type: Sensor type.
    ///   - value: Decoded value.
    public init(channel: UInt8, type: LPPSensorType, value: LPPValue) {
        self.channel = channel
        self.type = type
        self.value = value
    }
}

// MARK: - LPP Decoder

/// Decodes Cayenne Low Power Payload (LPP) format sensor data.
///
/// `LPPDecoder` parses binary LPP frames into structured ``LPPDataPoint`` values.
/// LPP is commonly used for transmitting sensor telemetry over LoRa networks.
///
/// ## Frame Format
///
/// Each LPP frame consists of multiple sensor readings:
/// - **Channel** (1 byte): Identifies the sensor instance (0-255)
/// - **Type** (1 byte): Sensor type from ``LPPSensorType``
/// - **Value** (N bytes): Type-specific encoded value
///
/// ## Usage
///
/// ```swift
/// // Decode telemetry response from a MeshCore device
/// let response = try await session.getSelfTelemetry()
/// let dataPoints = LPPDecoder.decode(response.rawData)
///
/// for point in dataPoints {
///     print("Channel \(point.channel) \(point.type.name):")
///     switch point.value {
///     case .float(let value):
///         print("  \(value)")
///     case .gps(let lat, let lon, let alt):
///         print("  \(lat), \(lon) @ \(alt)m")
///     default:
///         print("  \(point.value)")
///     }
/// }
/// ```
///
/// ## Supported Sensors
///
/// The decoder supports all standard Cayenne LPP sensor types including:
/// - Environmental: Temperature, humidity, barometer
/// - Motion: Accelerometer, gyrometer
/// - Location: GPS coordinates
/// - Electrical: Voltage, current, power
/// - Generic: Digital/analog I/O, percentages
public enum LPPDecoder {

    /// Decodes LPP data from raw bytes.
    ///
    /// - Parameter data: Raw LPP-encoded data bytes.
    /// - Returns: An array of decoded data points. Returns an empty array if
    ///            the data is empty or cannot be parsed.
    public static func decode(_ data: Data) -> [LPPDataPoint] {
        var result: [LPPDataPoint] = []
        var offset = 0

        while offset < data.count {
            guard offset + 2 <= data.count else { break }

            let channel = data[offset]
            let typeCode = data[offset + 1]
            offset += 2

            guard let sensorType = LPPSensorType(rawValue: typeCode) else {
                break
            }

            // Check we have enough data for this sensor type
            guard offset + sensorType.dataSize <= data.count else { break }

            let valueData = data.subdata(in: offset..<(offset + sensorType.dataSize))
            offset += sensorType.dataSize

            if let value = decodeValue(type: sensorType, data: valueData) {
                result.append(LPPDataPoint(channel: channel, type: sensorType, value: value))
            }
        }

        return result
    }

    // MARK: - Private Helpers

    private static func decodeValue(type: LPPSensorType, data: Data) -> LPPValue? {
        switch type {
        case .digitalInput, .digitalOutput, .presence, .switchValue:
            return .digital(data[0] != 0)

        case .percentage:
            return .integer(Int(data[0]))

        case .humidity:
            return .float(Double(data[0]) * 0.5)

        case .temperature:
            let raw = readInt16BE(data)
            return .float(Double(raw) / 10.0)

        case .barometer:
            let raw = readUInt16BE(data)
            return .float(Double(raw) / 10.0)

        case .voltage:
            // MeshCore firmware uses 0.01V units (multiplier 100)
            let raw = readUInt16BE(data)
            return .float(Double(raw) / 100.0)

        case .current:
            let raw = readInt16BE(data)
            return .float(Double(raw) / 1000.0)

        case .illuminance:
            let raw = readUInt16BE(data)
            return .integer(Int(raw))

        case .altitude:
            let raw = readInt16BE(data)
            return .float(Double(raw))

        case .load:
            let raw = readInt24BE(data, offset: 0)
            return .float(Double(raw) / 1000.0)

        case .concentration:
            let raw = readUInt16BE(data)
            return .integer(Int(raw))

        case .power:
            let raw = readUInt16BE(data)
            return .integer(Int(raw))

        case .direction:
            let raw = readUInt16BE(data)
            return .integer(Int(raw))

        case .analogInput, .analogOutput:
            let raw = readInt16BE(data)
            return .float(Double(raw) / 100.0)

        case .genericSensor:
            let raw = readUInt32BE(data)
            return .integer(Int(raw))

        case .frequency:
            let raw = readUInt32BE(data)
            return .integer(Int(raw))

        case .distance:
            let raw = readUInt32BE(data)
            return .float(Double(raw) / 1000.0)

        case .energy:
            let raw = readUInt32BE(data)
            return .float(Double(raw) / 1000.0)

        case .unixTime:
            let raw = readUInt32BE(data)
            return .timestamp(Date(timeIntervalSince1970: TimeInterval(raw)))

        case .accelerometer:
            let x = readInt16BE(data, offset: 0)
            let y = readInt16BE(data, offset: 2)
            let z = readInt16BE(data, offset: 4)
            return .vector3(
                x: Double(x) / 1000.0,
                y: Double(y) / 1000.0,
                z: Double(z) / 1000.0
            )

        case .gyrometer:
            let x = readInt16BE(data, offset: 0)
            let y = readInt16BE(data, offset: 2)
            let z = readInt16BE(data, offset: 4)
            return .vector3(
                x: Double(x) / 100.0,
                y: Double(y) / 100.0,
                z: Double(z) / 100.0
            )

        case .colour:
            return .rgb(red: data[0], green: data[1], blue: data[2])

        case .gps:
            // lat/lon: 0.0001° resolution, alt: 0.01m resolution
            let lat = readInt24BE(data, offset: 0)
            let lon = readInt24BE(data, offset: 3)
            let alt = readInt24BE(data, offset: 6)
            return .gps(
                latitude: Double(lat) / 10000.0,
                longitude: Double(lon) / 10000.0,
                altitude: Double(alt) / 100.0
            )
        }
    }

    // MARK: - Binary Reading Helpers (Big-Endian for MeshCore/LPP compatibility)

    private static func readInt16BE(_ data: Data, offset: Int = 0) -> Int16 {
        guard offset + 2 <= data.count else { return 0 }
        return Int16(data[offset]) << 8 | Int16(data[offset + 1])
    }

    private static func readUInt16BE(_ data: Data, offset: Int = 0) -> UInt16 {
        guard offset + 2 <= data.count else { return 0 }
        return UInt16(data[offset]) << 8 | UInt16(data[offset + 1])
    }

    private static func readUInt32BE(_ data: Data, offset: Int = 0) -> UInt32 {
        guard offset + 4 <= data.count else { return 0 }
        return UInt32(data[offset]) << 24
             | UInt32(data[offset + 1]) << 16
             | UInt32(data[offset + 2]) << 8
             | UInt32(data[offset + 3])
    }

    /// Read a 24-bit signed integer (big-endian)
    private static func readInt24BE(_ data: Data, offset: Int) -> Int32 {
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
}
