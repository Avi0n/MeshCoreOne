import Foundation
import Testing
@testable import MeshCore

@Suite("DeviceInfo Tolerant Parsing")
struct DeviceInfoParsingTests {

    // MARK: - Helpers

    /// Builds a v3+ DEVICE_INFO payload with configurable extension bytes.
    private func buildV3Payload(
        fwVer: UInt8 = 10,
        maxContactsHalf: UInt8 = 50,
        maxChannels: UInt8 = 8,
        blePin: UInt32 = 123456,
        fwBuild: String = "2025-01-01",
        model: String = "T-Deck",
        version: String = "1.12.0",
        includeClientRepeat: Bool = true,
        clientRepeat: UInt8 = 1,
        includePathHashMode: Bool = true,
        pathHashMode: UInt8 = 0
    ) -> Data {
        var data = Data()
        data.append(fwVer)
        data.append(maxContactsHalf)
        data.append(maxChannels)
        data.append(contentsOf: withUnsafeBytes(of: blePin.littleEndian) { Array($0) })
        var buildBytes = Array(fwBuild.utf8.prefix(12))
        buildBytes.append(contentsOf: [UInt8](repeating: 0, count: 12 - buildBytes.count))
        data.append(contentsOf: buildBytes)
        var modelBytes = Array(model.utf8.prefix(40))
        modelBytes.append(contentsOf: [UInt8](repeating: 0, count: 40 - modelBytes.count))
        data.append(contentsOf: modelBytes)
        var versionBytes = Array(version.utf8.prefix(20))
        versionBytes.append(contentsOf: [UInt8](repeating: 0, count: 20 - versionBytes.count))
        data.append(contentsOf: versionBytes)

        if includeClientRepeat {
            data.append(clientRepeat)
        }
        if includePathHashMode {
            data.append(pathHashMode)
        }
        return data
    }

    // MARK: - Full payload (baseline)

    @Test("Full v10 payload parses all fields")
    func fullV10PayloadParsesAllFields() {
        let data = buildV3Payload()
        guard case .deviceInfo(let caps) = Parsers.DeviceInfo.parse(data) else {
            Issue.record("Expected .deviceInfo, got different event")
            return
        }
        #expect(caps.firmwareVersion == 10)
        #expect(caps.maxContacts == 100)
        #expect(caps.maxChannels == 8)
        #expect(caps.blePin == 123456)
        #expect(caps.clientRepeat == true)
        #expect(caps.pathHashMode == 0)
    }

    // MARK: - Missing extension bytes (full v3 base block present)

    @Test("v9 payload missing client_repeat byte defaults to false")
    func v9MissingClientRepeatDefaultsToFalse() {
        let data = buildV3Payload(fwVer: 9, includeClientRepeat: false, includePathHashMode: false)
        #expect(data.count == 79, "v9 payload without extensions should be 79 bytes")
        guard case .deviceInfo(let caps) = Parsers.DeviceInfo.parse(data) else {
            Issue.record("Expected .deviceInfo, got parseFailure")
            return
        }
        #expect(caps.firmwareVersion == 9)
        #expect(caps.maxContacts == 100)
        #expect(caps.clientRepeat == false)
    }

    @Test("v10 payload missing pathHashMode byte defaults to 0")
    func v10MissingPathHashModeDefaultsToZero() {
        let data = buildV3Payload(fwVer: 10, includePathHashMode: false)
        #expect(data.count == 80, "v10 payload with client_repeat but no pathHashMode")
        guard case .deviceInfo(let caps) = Parsers.DeviceInfo.parse(data) else {
            Issue.record("Expected .deviceInfo, got parseFailure")
            return
        }
        #expect(caps.firmwareVersion == 10)
        #expect(caps.clientRepeat == true)
        #expect(caps.pathHashMode == 0)
        #expect(caps.hashSize == 1)
    }

    @Test("v10 payload missing both extension bytes defaults both")
    func v10MissingBothExtensionBytesDefaultsBoth() {
        let data = buildV3Payload(fwVer: 10, includeClientRepeat: false, includePathHashMode: false)
        #expect(data.count == 79, "v10 payload without any extensions should be 79 bytes")
        guard case .deviceInfo(let caps) = Parsers.DeviceInfo.parse(data) else {
            Issue.record("Expected .deviceInfo, got parseFailure")
            return
        }
        #expect(caps.firmwareVersion == 10)
        #expect(caps.maxContacts == 100, "v3 base fields should still parse")
        #expect(caps.clientRepeat == false, "Missing client_repeat should default to false")
        #expect(caps.pathHashMode == 0, "Missing pathHashMode should default to 0")
    }

    // MARK: - v3 base block guard (must still reject truncated base)

    @Test("v3+ payload shorter than 79 bytes is rejected")
    func shortV3PayloadIsRejected() {
        var data = Data([10])  // fwVer=10
        data.append(contentsOf: [UInt8](repeating: 0, count: 9))  // 10 bytes total
        guard case .parseFailure = Parsers.DeviceInfo.parse(data) else {
            Issue.record("Expected parseFailure for truncated v3 base block")
            return
        }
    }

    // MARK: - Edge cases

    @Test("Empty payload still returns parseFailure")
    func emptyPayloadReturnsFailure() {
        let data = Data()
        guard case .parseFailure = Parsers.DeviceInfo.parse(data) else {
            Issue.record("Expected parseFailure for empty data")
            return
        }
    }

    @Test("Pre-v3 firmware parses without v3 fields")
    func preV3FirmwareParsesMinimally() {
        let data = Data([2])
        guard case .deviceInfo(let caps) = Parsers.DeviceInfo.parse(data) else {
            Issue.record("Expected .deviceInfo for pre-v3 firmware")
            return
        }
        #expect(caps.firmwareVersion == 2)
        #expect(caps.maxContacts == 0)
        #expect(caps.model == "")
    }
}
