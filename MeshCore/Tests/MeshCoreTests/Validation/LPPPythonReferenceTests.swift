import Foundation
@testable import MeshCore
import Testing

/// Tests that verify Swift LPPEncoder produces bytes matching Python cayennelpp library.
///
/// These tests compare Swift-generated LPP payloads against reference bytes extracted from
/// the Python cayennelpp library, ensuring byte-level protocol compatibility.
///
/// Note: Swift LPPEncoder uses MeshCore's voltage type (0x74) while Python cayennelpp
/// uses analogInput (0x02) for voltage. Tests use analogInput for cross-library compatibility.
@Suite("LPP Python Reference")
struct LPPPythonReferenceTests {
  // MARK: - Temperature Tests

  @Test
  func `Temperature 25.5 matches Python`() {
    // Python: LppFrame().add_temperature(1, 25.5)
    // Format: channel(1) + type(0x67) + value(int16 BE, *10)
    // 25.5 * 10 = 255 = 0x00FF
    var encoder = LPPEncoder()
    encoder.addTemperature(channel: 1, celsius: 25.5)
    let encoded = encoder.encode()
    #expect(encoded == PythonReferenceBytes.lpp_temperature_25_5,
            "temperature mismatch - Swift: \(encoded.hexString), Python: \(PythonReferenceBytes.lpp_temperature_25_5.hexString)")
  }

  @Test
  func `Temperature negative round trip`() {
    // Verify negative temperatures encode/decode correctly
    var encoder = LPPEncoder()
    encoder.addTemperature(channel: 1, celsius: -10.5)
    let encoded = encoder.encode()

    // -10.5 * 10 = -105 in signed int16 big-endian = 0xFF97
    #expect(encoded == Data([0x01, 0x67, 0xFF, 0x97]),
            "negative temperature encoding mismatch")

    // Verify decode matches
    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    if case let .float(value) = decoded[0].value {
      #expect(abs(value - -10.5) <= 0.1)
    } else {
      Issue.record("Expected float value")
    }
  }

  // MARK: - Humidity Tests

  @Test
  func `Humidity 65 matches Python`() {
    // Python: LppFrame().add_humidity(2, 65.0)
    // Format: channel(1) + type(0x68) + value(uint8, *2)
    // 65 * 2 = 130 = 0x82
    var encoder = LPPEncoder()
    encoder.addHumidity(channel: 2, percent: 65.0)
    let encoded = encoder.encode()
    #expect(encoded == PythonReferenceBytes.lpp_humidity_65,
            "humidity mismatch - Swift: \(encoded.hexString), Python: \(PythonReferenceBytes.lpp_humidity_65.hexString)")
  }

  // MARK: - Analog Input Tests

  @Test
  func `Analog input 3.3 matches Python`() {
    // Python: LppFrame().add_analog_input(3, 3.3)
    // Format: channel(1) + type(0x02) + value(int16 BE, *100)
    // 3.3 * 100 = 330 = 0x014A
    var encoder = LPPEncoder()
    encoder.addAnalogInput(channel: 3, value: 3.3)
    let encoded = encoder.encode()
    #expect(encoded == PythonReferenceBytes.lpp_analog_3_3,
            "analogInput mismatch - Swift: \(encoded.hexString), Python: \(PythonReferenceBytes.lpp_analog_3_3.hexString)")
  }

  // MARK: - GPS Tests

  @Test
  func `GPS SF matches Python`() {
    // Python: LppFrame().add_gps(4, 37.7749, -122.4194, 10.0)
    var encoder = LPPEncoder()
    encoder.addGPS(channel: 4, latitude: 37.7749, longitude: -122.4194, altitude: 10.0)
    let encoded = encoder.encode()
    #expect(encoded == PythonReferenceBytes.lpp_gps_sf,
            "gps mismatch - Swift: \(encoded.hexString), Python: \(PythonReferenceBytes.lpp_gps_sf.hexString)")
  }

  @Test
  func `GPS decode round trip`() {
    // Verify GPS decode matches encode
    var encoder = LPPEncoder()
    encoder.addGPS(channel: 4, latitude: 37.7749, longitude: -122.4194, altitude: 10.0)
    let encoded = encoder.encode()

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    #expect(decoded[0].channel == 4)
    #expect(decoded[0].type == .gps)

    if case let .gps(lat, lon, alt) = decoded[0].value {
      #expect(abs(lat - 37.7749) <= 0.0001)
      #expect(abs(lon - -122.4194) <= 0.0001)
      #expect(abs(alt - 10.0) <= 0.01)
    } else {
      Issue.record("Expected GPS value")
    }
  }

  // MARK: - Barometer Tests

  @Test
  func `Barometer 1013 matches Python`() {
    var encoder = LPPEncoder()
    encoder.addBarometer(channel: 5, hPa: 1013.2) // Use 1013.2 to match Python truncation
    let encoded = encoder.encode()
    #expect(encoded == PythonReferenceBytes.lpp_barometer_1013,
            "barometer mismatch - Swift: \(encoded.hexString), Python: \(PythonReferenceBytes.lpp_barometer_1013.hexString)")
  }

  // MARK: - Accelerometer Tests

  @Test
  func `Accelerometer 1g matches Python`() {
    var encoder = LPPEncoder()
    encoder.addAccelerometer(channel: 6, x: 0.0, y: 0.0, z: 1.0)
    let encoded = encoder.encode()
    #expect(encoded == PythonReferenceBytes.lpp_accelerometer_1g,
            "accelerometer mismatch - Swift: \(encoded.hexString), Python: \(PythonReferenceBytes.lpp_accelerometer_1g.hexString)")
  }

  @Test
  func `Accelerometer decode round trip`() {
    // Verify accelerometer decode matches encode
    var encoder = LPPEncoder()
    encoder.addAccelerometer(channel: 6, x: 0.5, y: -0.5, z: 1.0)
    let encoded = encoder.encode()

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    #expect(decoded[0].channel == 6)
    #expect(decoded[0].type == .accelerometer)

    if case let .vector3(x, y, z) = decoded[0].value {
      #expect(abs(x - 0.5) <= 0.001)
      #expect(abs(y - -0.5) <= 0.001)
      #expect(abs(z - 1.0) <= 0.001)
    } else {
      Issue.record("Expected vector3 value")
    }
  }

  // MARK: - Multi-Sensor Tests

  @Test
  func `Multi-sensor payload`() {
    // Build a payload with multiple sensors like a real device would
    var encoder = LPPEncoder()
    encoder.addTemperature(channel: 1, celsius: 25.5)
    encoder.addHumidity(channel: 2, percent: 65.0)
    encoder.addBarometer(channel: 3, hPa: 1013.2)
    let encoded = encoder.encode()

    // Verify we can decode all values
    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 3)

    // Temperature
    #expect(decoded[0].channel == 1)
    #expect(decoded[0].type == .temperature)
    if case let .float(temp) = decoded[0].value {
      #expect(abs(temp - 25.5) <= 0.1)
    }

    // Humidity
    #expect(decoded[1].channel == 2)
    #expect(decoded[1].type == .humidity)
    if case let .float(hum) = decoded[1].value {
      #expect(abs(hum - 65.0) <= 0.5)
    }

    // Barometer
    #expect(decoded[2].channel == 3)
    #expect(decoded[2].type == .barometer)
    if case let .float(pressure) = decoded[2].value {
      #expect(abs(pressure - 1013.2) <= 0.1)
    }
  }

  // MARK: - Voltage Tests (MeshCore-specific)

  @Test
  func `Voltage encoding`() {
    // MeshCore uses voltage type (0x74) which differs from Python cayennelpp's analogInput (0x02)
    var encoder = LPPEncoder()
    encoder.addVoltage(channel: 1, volts: 3.8)
    let encoded = encoder.encode()

    // channel(1) + type(0x74) + value(uint16 BE, *100)
    // 3.8 * 100 = 380 = 0x017C
    #expect(encoded == Data([0x01, 0x74, 0x01, 0x7C]),
            "voltage encoding mismatch - Swift: \(encoded.hexString)")

    // Verify decode
    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    #expect(decoded[0].type == .voltage)
    if case let .float(volts) = decoded[0].value {
      #expect(abs(volts - 3.8) <= 0.01)
    }
  }

  // MARK: - Edge Cases

  @Test
  func `Illuminance encoding`() {
    var encoder = LPPEncoder()
    encoder.addIlluminance(channel: 1, lux: 1000)
    let encoded = encoder.encode()

    // channel(1) + type(0x65) + value(uint16 BE)
    // 1000 = 0x03E8
    #expect(encoded == Data([0x01, 0x65, 0x03, 0xE8]))

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    if case let .integer(lux) = decoded[0].value {
      #expect(lux == 1000)
    } else {
      Issue.record("Expected integer value")
    }
  }

  @Test
  func `Digital IO encoding`() {
    var encoder = LPPEncoder()
    encoder.addDigitalInput(channel: 1, value: 1)
    encoder.addDigitalOutput(channel: 2, value: 0)
    let encoded = encoder.encode()

    // Digital input: channel(1) + type(0x00) + value(1)
    // Digital output: channel(2) + type(0x01) + value(0)
    #expect(encoded == Data([0x01, 0x00, 0x01, 0x02, 0x01, 0x00]))

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 2)

    if case let .digital(din) = decoded[0].value {
      #expect(din)
    } else {
      Issue.record("Expected digital value for input")
    }
    if case let .digital(dout) = decoded[1].value {
      #expect(!dout)
    } else {
      Issue.record("Expected digital value for output")
    }
  }

  @Test
  func `Gyrometer encoding`() {
    var encoder = LPPEncoder()
    encoder.addGyrometer(channel: 1, x: 10.5, y: -5.25, z: 0.0)
    let encoded = encoder.encode()

    // x: 10.5 * 100 = 1050 = 0x041A
    // y: -5.25 * 100 = -525 = 0xFDF3 (signed)
    // z: 0.0 * 100 = 0 = 0x0000
    #expect(encoded == Data([0x01, 0x86, 0x04, 0x1A, 0xFD, 0xF3, 0x00, 0x00]))

    let decoded = LPPDecoder.decode(encoded)
    #expect(decoded.count == 1)
    if case let .vector3(x, y, z) = decoded[0].value {
      #expect(abs(x - 10.5) <= 0.01)
      #expect(abs(y - -5.25) <= 0.01)
      #expect(abs(z - 0.0) <= 0.01)
    } else {
      Issue.record("Expected vector3 value")
    }
  }

  // MARK: - Load Tests (type 122, 3-byte signed, 0.001 kg)

  @Test
  func `Load positive decodes as 3-byte signed divided by 1000`() {
    // No addLoad encoder exists, so build the raw frame by hand.
    // channel(0x07) + type(0x7A = 122) + value(int24 BE, *1000)
    // 12.345 kg * 1000 = 12345 = 0x003039
    let frame = Data([0x07, 0x7A, 0x00, 0x30, 0x39])
    let decoded = LPPDecoder.decode(frame)
    #expect(decoded.count == 1)
    #expect(decoded[0].channel == 0x07)
    #expect(decoded[0].type == .load)
    if case let .float(value) = decoded[0].value {
      #expect(abs(value - 12.345) <= 0.001)
    } else {
      Issue.record("Expected float value for load")
    }
  }

  @Test
  func `Load negative round trips through 24-bit sign extension`() {
    // -1.5 kg * 1000 = -1500 = 0xFFFA24 (24-bit two's complement)
    let frame = Data([0x07, 0x7A, 0xFF, 0xFA, 0x24])
    let decoded = LPPDecoder.decode(frame)
    #expect(decoded.count == 1)
    #expect(decoded[0].type == .load)
    if case let .float(value) = decoded[0].value {
      #expect(abs(value - -1.5) <= 0.001)
    } else {
      Issue.record("Expected float value for load")
    }
  }

  @Test
  func `Load consumes three bytes so the next datum stays aligned`() {
    // Load (3 bytes) followed by a temperature reading; if Load were
    // mis-sized at 2 bytes the temperature would be misaligned or dropped.
    // Load: 1.000 kg = 1000 = 0x0003E8
    // Temp: 25.5 C = 255 = 0x00FF
    let frame = Data([0x07, 0x7A, 0x00, 0x03, 0xE8, 0x01, 0x67, 0x00, 0xFF])
    let decoded = LPPDecoder.decode(frame)
    #expect(decoded.count == 2)
    #expect(decoded[0].type == .load)
    if case let .float(load) = decoded[0].value {
      #expect(abs(load - 1.0) <= 0.001)
    } else {
      Issue.record("Expected float value for load")
    }
    #expect(decoded[1].type == .temperature)
    if case let .float(temp) = decoded[1].value {
      #expect(abs(temp - 25.5) <= 0.1)
    } else {
      Issue.record("Expected float value for temperature")
    }
  }

  // MARK: - Generic Sensor Tests (type 100, 4-byte unsigned)

  @Test
  func `Generic sensor decodes high bit set as a large positive integer`() {
    // No addGenericSensor encoder exists, so build the raw frame by hand.
    // channel(0x01) + type(0x64 = 100) + value(uint32 BE)
    // 0x80000000 = 2_147_483_648; a signed decode would yield -2_147_483_648.
    let frame = Data([0x01, 0x64, 0x80, 0x00, 0x00, 0x00])
    let decoded = LPPDecoder.decode(frame)
    #expect(decoded.count == 1)
    #expect(decoded[0].type == .genericSensor)
    if case let .integer(value) = decoded[0].value {
      #expect(value == 2_147_483_648)
    } else {
      Issue.record("Expected integer value for generic sensor")
    }
  }
}
