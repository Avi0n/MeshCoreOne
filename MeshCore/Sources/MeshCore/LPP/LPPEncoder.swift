import Foundation

/// Encodes sensor data into Cayenne Low Power Payload (LPP) format.
///
/// LPP is a compact binary format for transmitting sensor data over
/// low-bandwidth networks. Each data point is encoded as:
/// - Channel (1 byte): Identifies the sensor instance
/// - Type (1 byte): Sensor type from ``LPPSensorType``
/// - Value (variable): Type-specific encoded value
///
/// ## Usage
///
/// ```swift
/// var encoder = LPPEncoder()
/// encoder.addTemperature(channel: 1, celsius: 25.5)
/// encoder.addHumidity(channel: 2, percent: 65)
/// encoder.addGPS(channel: 3, latitude: 37.7749, longitude: -122.4194, altitude: 10)
/// let payload = encoder.encode()
/// ```
///
/// ## Round-Trip Compatibility
///
/// Encoded payloads can be decoded using ``LPPDecoder``:
///
/// ```swift
/// var encoder = LPPEncoder()
/// encoder.addTemperature(channel: 1, celsius: 22.5)
/// let payload = encoder.encode()
///
/// let decoded = LPPDecoder.decode(payload)
/// // decoded[0].value == .float(22.5)
/// ```
public struct LPPEncoder: Sendable {
  private var buffer: Data

  /// Creates a new empty encoder.
  public init() {
    buffer = Data()
  }

  /// The current encoded payload size in bytes.
  public var count: Int {
    buffer.count
  }

  /// Resets the encoder, clearing all buffered data.
  public mutating func reset() {
    buffer.removeAll()
  }

  /// Returns the encoded payload.
  ///
  /// - Returns: A `Data` object containing the LPP-encoded payload.
  public func encode() -> Data {
    buffer
  }

  // MARK: - Digital I/O

  /// Adds a digital input value.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - value: Digital value (0 or 1).
  public mutating func addDigitalInput(channel: UInt8, value: UInt8) {
    buffer.append(channel)
    buffer.append(LPPSensorType.digitalInput.rawValue)
    buffer.append(value != 0 ? 1 : 0)
  }

  /// Adds a digital output value.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - value: Digital value (0 or 1).
  public mutating func addDigitalOutput(channel: UInt8, value: UInt8) {
    buffer.append(channel)
    buffer.append(LPPSensorType.digitalOutput.rawValue)
    buffer.append(value != 0 ? 1 : 0)
  }

  // MARK: - Analog I/O

  /// Adds an analog input value.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - value: Analog value (0.01 resolution, range -327.68 to 327.67).
  public mutating func addAnalogInput(channel: UInt8, value: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.analogInput.rawValue)
    appendInt16(Int16(value * 100))
  }

  /// Adds an analog output value.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - value: Analog value (0.01 resolution, range -327.68 to 327.67).
  public mutating func addAnalogOutput(channel: UInt8, value: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.analogOutput.rawValue)
    appendInt16(Int16(value * 100))
  }

  // MARK: - Environmental

  /// Adds a temperature reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - celsius: Temperature in Celsius (0.1 resolution).
  public mutating func addTemperature(channel: UInt8, celsius: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.temperature.rawValue)
    appendInt16(Int16(celsius * 10))
  }

  /// Adds a humidity reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - percent: Relative humidity 0-100 (0.5 resolution).
  public mutating func addHumidity(channel: UInt8, percent: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.humidity.rawValue)
    buffer.append(UInt8(percent * 2))
  }

  /// Adds a barometric pressure reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - hPa: Pressure in hectopascals (0.1 resolution).
  public mutating func addBarometer(channel: UInt8, hPa: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.barometer.rawValue)
    appendUInt16(UInt16(hPa * 10))
  }

  /// Adds an illuminance reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - lux: Illuminance in lux (1 lux resolution).
  public mutating func addIlluminance(channel: UInt8, lux: UInt16) {
    buffer.append(channel)
    buffer.append(LPPSensorType.illuminance.rawValue)
    appendUInt16(lux)
  }

  // MARK: - Motion

  /// Adds an accelerometer reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - x: X-axis acceleration in G (0.001 resolution).
  ///   - y: Y-axis acceleration in G.
  ///   - z: Z-axis acceleration in G.
  public mutating func addAccelerometer(channel: UInt8, x: Double, y: Double, z: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.accelerometer.rawValue)
    appendInt16(Int16(x * 1000))
    appendInt16(Int16(y * 1000))
    appendInt16(Int16(z * 1000))
  }

  /// Adds a gyrometer reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - x: X-axis rotation in deg/s (0.01 resolution).
  ///   - y: Y-axis rotation in deg/s.
  ///   - z: Z-axis rotation in deg/s.
  public mutating func addGyrometer(channel: UInt8, x: Double, y: Double, z: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.gyrometer.rawValue)
    appendInt16(Int16(x * 100))
    appendInt16(Int16(y * 100))
    appendInt16(Int16(z * 100))
  }

  // MARK: - Location

  /// Adds a GPS location.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - latitude: Latitude in degrees (-90 to 90, 0.0001 resolution).
  ///   - longitude: Longitude in degrees (-180 to 180, 0.0001 resolution).
  ///   - altitude: Altitude in meters (0.01 resolution).
  public mutating func addGPS(channel: UInt8, latitude: Double, longitude: Double, altitude: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.gps.rawValue)
    appendInt24(Int32(latitude * 10000))
    appendInt24(Int32(longitude * 10000))
    appendInt24(Int32(altitude * 100))
  }

  // MARK: - Electrical

  /// Adds a voltage reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - volts: Voltage in volts (0.01V resolution per MeshCore firmware).
  public mutating func addVoltage(channel: UInt8, volts: Double) {
    buffer.append(channel)
    buffer.append(LPPSensorType.voltage.rawValue)
    // MeshCore firmware uses 0.01V units (multiplier 100)
    appendUInt16(UInt16(volts * 100))
  }

  /// Adds a current reading.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - milliamps: Current in milliamps (0.001A resolution).
  public mutating func addCurrent(channel: UInt8, milliamps: UInt16) {
    buffer.append(channel)
    buffer.append(LPPSensorType.current.rawValue)
    appendUInt16(milliamps)
  }

  // MARK: - Generic

  /// Adds a raw data point.
  ///
  /// - Parameters:
  ///   - channel: Sensor channel (0-255).
  ///   - type: Sensor type.
  ///   - data: Raw encoded data (must match type's dataSize).
  public mutating func addRaw(channel: UInt8, type: LPPSensorType, data: Data) {
    precondition(data.count == type.dataSize, "Data size must match sensor type")
    buffer.append(channel)
    buffer.append(type.rawValue)
    buffer.append(data)
  }

  // MARK: - Private Helpers (Big-Endian for MeshCore/LPP compatibility)

  private mutating func appendInt16(_ value: Int16) {
    buffer.append(UInt8((value >> 8) & 0xFF)) // High byte first
    buffer.append(UInt8(value & 0xFF)) // Low byte second
  }

  private mutating func appendUInt16(_ value: UInt16) {
    buffer.append(UInt8((value >> 8) & 0xFF)) // High byte first
    buffer.append(UInt8(value & 0xFF)) // Low byte second
  }

  private mutating func appendInt24(_ value: Int32) {
    // Big-endian 24-bit signed
    buffer.append(UInt8((value >> 16) & 0xFF)) // High byte first
    buffer.append(UInt8((value >> 8) & 0xFF))
    buffer.append(UInt8(value & 0xFF)) // Low byte last
  }
}
