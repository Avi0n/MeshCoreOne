import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

extension SettingsIdentityMetadataProvenanceTests {
  static let telemetryMarker: UInt8 = 1
  static let authenticatedTelemetryBase: UInt8 = 0
  static let telemetryModes = TelemetryModes(base: telemetryMarker, location: 0, environment: 0)
  static let importedPublicKey = Data([
    0x1E, 0xC7, 0x71, 0x75, 0xB0, 0x91, 0x8E, 0xD2, 0x06, 0xF9, 0xAE, 0x04, 0xEC, 0x13, 0x6D, 0x6D,
    0x5D, 0x43, 0x15, 0xBB, 0x26, 0x30, 0x54, 0x27, 0xF6, 0x45, 0xB4, 0x92, 0xE9, 0x35, 0x0C, 0x10
  ])
  static let expandedKeyHex = "7065e18fd9fabb70c1ed90dca19907de698c88b709ea146eafd93d9b830c7b60"
    + "c4681193c79bbc39945ba8064104bb618f8fd7a84a0af6f57033d6e8ddcd6471"
  static let config = MeshCoreNodeConfig(privateKey: expandedKeyHex, otherSettings: .init(multiAcks: 1))
  static let selectedTailPacket = PacketBuilder.setOtherParams(
    manualAddContacts: false, telemetryModeEnvironment: 0, telemetryModeLocation: 0,
    telemetryModeBase: authenticatedTelemetryBase, advertisementLocationPolicy: 0, multiAcks: 1
  )
  static let streamFenceMarker: UInt8 = 1
  static let continuationPreset = RadioPreset(
    id: "continuation-source", name: "Continuation source", region: .northAmerica,
    frequencyMHz: 915, bandwidthKHz: 125, spreadingFactor: 7, codingRate: 5,
    pathHashSize: 1, availability: .countries(["US"])
  )

  static func telemetryPacket(publicKey: Data) -> Data {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(publicKey)
    packet.append(Data(repeating: 0, count: 8))
    packet.append(contentsOf: [0, 0, telemetryMarker, 0])
    for value: UInt32 in [915_000, 125_000] {
      packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
    }
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test Radio".utf8)
    return packet
  }

  func selectFile(_ config: MeshCoreNodeConfig, model: NodeConfigImportViewModel) async throws {
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let url = directory.appendingPathComponent("config.json")
    try JSONEncoder().encode(config).write(to: url)
    model.parseFile(at: url)
    await model.parseTask?.value
    let selectedInputWasParsed = model.importedConfig != nil && !model.isParsing && model.errorMessage == nil
    try #require(selectedInputWasParsed)
    let onlyIdentityAndOtherSettingsAreSelected = model.sections.nodeIdentity && model.sections.otherSettings
      && !model.sections.radioSettings && !model.sections.positionSettings && !model.sections.channels && !model.sections.contacts
    try #require(onlyIdentityAndOtherSettingsAreSelected)
  }

  func waitUntil(_ reason: String, _ predicate: @escaping @MainActor () async -> Bool) async throws {
    let deadline = ContinuousClock.now + Timing.timeout
    while await !predicate() {
      guard ContinuousClock.now < deadline else {
        throw NSError(domain: "SettingsIdentityMetadataProvenance", code: 1, userInfo: [NSLocalizedDescriptionKey: reason])
      }
      try await Task.sleep(for: Timing.pollInterval)
    }
  }

  @MainActor
  final class Gate {
    private(set) var arrived = false
    private var continuations: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false

    func wait() async {
      arrived = true
      guard !isOpen else { return }
      await withCheckedContinuation { continuations.append($0) }
    }

    func open() {
      isOpen = true
      let pending = continuations
      continuations.removeAll()
      for continuation in pending {
        continuation.resume()
      }
    }
  }

  private enum Timing {
    static let timeout: Duration = .seconds(35)
    static let pollInterval: Duration = .milliseconds(1)
  }
}
