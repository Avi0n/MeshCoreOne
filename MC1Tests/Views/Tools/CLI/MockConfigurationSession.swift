import Foundation
@testable import MeshCore

/// A stateful fake `ConfigurationSessionOps` for exercising a real `SettingsService`
/// over recorded invocations. Setters mutate the stored `SelfInfo`/`DeviceCapabilities`
/// so the verified setters' read-backs reflect the write; flip `suppressWrites` to
/// leave state unchanged and drive the `verificationFailed` path.
actor MockConfigurationSession: ConfigurationSessionOps {
  private var selfInfo: SelfInfo
  private var capabilities: DeviceCapabilities
  private var battery: BatteryInfo
  private var deviceTime: Date
  private var customVars: [String: String]
  private var suppressWrites = false
  private var nextSetCustomVarErrorCode: UInt8?
  private var nextGetCustomVarsErrorCode: UInt8?

  private(set) var readCount = 0
  private(set) var rebootCalled = false
  private(set) var setNameCalls: [String] = []
  private(set) var setTxPowerCalls: [Int8] = []
  private(set) var setPathHashModeCalls: [UInt8] = []
  private(set) var setTimeCalls: [Date] = []
  private(set) var setRadioCalls: [(frequency: Double, bandwidth: Double, spreadingFactor: UInt8, codingRate: UInt8)] = []
  private(set) var setCoordinatesCalls: [(latitude: Double, longitude: Double)] = []
  private(set) var setOtherParamsCalls: [(manualAddContacts: Bool, multiAcks: UInt8?)] = []
  private(set) var setCustomVarCalls: [(key: String, value: String)] = []

  init(
    name: String = "TestDevice",
    txPower: Int8 = 20,
    publicKey: Data = Data(repeating: 0x01, count: 32),
    latitude: Double = 0,
    longitude: Double = 0,
    multiAcks: UInt8 = 0,
    manualAddContacts: Bool = false,
    radioFrequency: Double = 869.525,
    radioBandwidth: Double = 250,
    radioSpreadingFactor: UInt8 = 11,
    radioCodingRate: UInt8 = 5,
    version: String = "1.13.0",
    firmwareBuild: String = "testbuild",
    model: String = "TestBoard",
    pathHashMode: UInt8 = 0,
    batteryMillivolts: Int = 3700,
    deviceTime: Date = Date(timeIntervalSince1970: 0),
    customVars: [String: String] = [:]
  ) {
    self.customVars = customVars
    selfInfo = SelfInfo(
      advertisementType: 0,
      txPower: txPower,
      maxTxPower: 30,
      publicKey: publicKey,
      latitude: latitude,
      longitude: longitude,
      multiAcks: multiAcks,
      advertisementLocationPolicy: 0,
      telemetryModeEnvironment: 0,
      telemetryModeLocation: 0,
      telemetryModeBase: 0,
      manualAddContacts: manualAddContacts,
      radioFrequency: radioFrequency,
      radioBandwidth: radioBandwidth,
      radioSpreadingFactor: radioSpreadingFactor,
      radioCodingRate: radioCodingRate,
      name: name
    )
    capabilities = DeviceCapabilities(
      firmwareVersion: 9,
      maxContacts: 100,
      maxChannels: 8,
      blePin: 0,
      firmwareBuild: firmwareBuild,
      model: model,
      version: version,
      clientRepeat: false,
      pathHashMode: pathHashMode
    )
    battery = BatteryInfo(level: batteryMillivolts)
    self.deviceTime = deviceTime
  }

  func setSuppressWrites(_ suppress: Bool) {
    suppressWrites = suppress
  }

  // MARK: - Device Info

  func sendAppStart() async throws -> SelfInfo {
    readCount += 1
    return selfInfo
  }

  func queryDevice() async throws -> DeviceCapabilities {
    readCount += 1
    return capabilities
  }

  func getBattery() async throws -> BatteryInfo {
    readCount += 1
    return battery
  }

  func getTime() async throws -> Date {
    readCount += 1
    return deviceTime
  }

  func setTime(_ date: Date) async throws {
    setTimeCalls.append(date)
    guard !suppressWrites else { return }
    deviceTime = date
  }

  // MARK: - Device Configuration

  func setName(_ name: String) async throws {
    setNameCalls.append(name)
    guard !suppressWrites else { return }
    selfInfo = patched(name: name)
  }

  func setCoordinates(latitude: Double, longitude: Double) async throws {
    setCoordinatesCalls.append((latitude, longitude))
    guard !suppressWrites else { return }
    selfInfo = patched(latitude: latitude, longitude: longitude)
  }

  func setTxPower(_ power: Int8) async throws {
    setTxPowerCalls.append(power)
    guard !suppressWrites else { return }
    selfInfo = patched(txPower: power)
  }

  func setRadio(
    frequency: Double,
    bandwidth: Double,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    clientRepeat: Bool?
  ) async throws {
    setRadioCalls.append((frequency, bandwidth, spreadingFactor, codingRate))
    guard !suppressWrites else { return }
    selfInfo = patched(
      radioFrequency: frequency,
      radioBandwidth: bandwidth,
      radioSpreadingFactor: spreadingFactor,
      radioCodingRate: codingRate
    )
  }

  func setOtherParams(
    manualAddContacts: Bool,
    telemetryModeEnvironment: UInt8,
    telemetryModeLocation: UInt8,
    telemetryModeBase: UInt8,
    advertisementLocationPolicy: UInt8,
    multiAcks: UInt8?
  ) async throws {
    setOtherParamsCalls.append((manualAddContacts, multiAcks))
    guard !suppressWrites else { return }
    selfInfo = patched(multiAcks: multiAcks ?? selfInfo.multiAcks, manualAddContacts: manualAddContacts)
  }

  func setPathHashMode(_ mode: UInt8) async throws {
    setPathHashModeCalls.append(mode)
    guard !suppressWrites else { return }
    capabilities = patched(pathHashMode: mode)
  }

  func reboot() async throws {
    rebootCalled = true
  }

  // MARK: - Custom Variables

  /// Make the next `setCustomVar` throw `MeshCoreError.deviceError(code:)`,
  /// exercising the real `SettingsService` wrap into `.sessionError`.
  func failNextSetCustomVar(code: UInt8) {
    nextSetCustomVarErrorCode = code
  }

  /// Make the next `getCustomVars` throw `MeshCoreError.deviceError(code:)`,
  /// standing in for firmware that can't enumerate custom vars.
  func failNextGetCustomVars(code: UInt8) {
    nextGetCustomVarsErrorCode = code
  }

  func getCustomVars() async throws -> [String: String] {
    readCount += 1
    if let code = nextGetCustomVarsErrorCode {
      nextGetCustomVarsErrorCode = nil
      throw MeshCoreError.deviceError(code: code)
    }
    return customVars
  }

  func setCustomVar(key: String, value: String) async throws {
    setCustomVarCalls.append((key, value))
    if let code = nextSetCustomVarErrorCode {
      nextSetCustomVarErrorCode = nil
      throw MeshCoreError.deviceError(code: code)
    }
    guard !suppressWrites else { return }
    customVars[key] = value
  }

  // MARK: - Unused requirements

  func getRepeatFreq() async throws -> [FrequencyRange] {
    throw MeshCoreError.timeout
  }

  func setDevicePin(_ pin: UInt32) async throws {}
  func getAutoAddConfig() async throws -> AutoAddConfig {
    throw MeshCoreError.timeout
  }

  func setAutoAddConfig(_ config: AutoAddConfig) async throws {}
  func setDefaultFloodScope(name: String, scope: FloodScope) async throws {}
  func getDefaultFloodScope() async throws -> DefaultFloodScope? {
    nil
  }

  func factoryReset() async throws {}
  func getStatsCore() async throws -> CoreStats {
    throw MeshCoreError.timeout
  }

  func getStatsRadio() async throws -> RadioStats {
    throw MeshCoreError.timeout
  }

  func getStatsPackets() async throws -> PacketStats {
    throw MeshCoreError.timeout
  }

  func exportPrivateKey() async throws -> Data {
    throw MeshCoreError.timeout
  }

  func importPrivateKey(_ key: Data) async throws {}
  func sign(_ data: Data, chunkSize: Int, timeout: TimeInterval?) async throws -> Data {
    throw MeshCoreError.timeout
  }

  // MARK: - Patch helpers

  private func patched(
    txPower: Int8? = nil,
    latitude: Double? = nil,
    longitude: Double? = nil,
    multiAcks: UInt8? = nil,
    manualAddContacts: Bool? = nil,
    radioFrequency: Double? = nil,
    radioBandwidth: Double? = nil,
    radioSpreadingFactor: UInt8? = nil,
    radioCodingRate: UInt8? = nil,
    name: String? = nil
  ) -> SelfInfo {
    SelfInfo(
      advertisementType: selfInfo.advertisementType,
      txPower: txPower ?? selfInfo.txPower,
      maxTxPower: selfInfo.maxTxPower,
      publicKey: selfInfo.publicKey,
      latitude: latitude ?? selfInfo.latitude,
      longitude: longitude ?? selfInfo.longitude,
      multiAcks: multiAcks ?? selfInfo.multiAcks,
      advertisementLocationPolicy: selfInfo.advertisementLocationPolicy,
      telemetryModeEnvironment: selfInfo.telemetryModeEnvironment,
      telemetryModeLocation: selfInfo.telemetryModeLocation,
      telemetryModeBase: selfInfo.telemetryModeBase,
      manualAddContacts: manualAddContacts ?? selfInfo.manualAddContacts,
      radioFrequency: radioFrequency ?? selfInfo.radioFrequency,
      radioBandwidth: radioBandwidth ?? selfInfo.radioBandwidth,
      radioSpreadingFactor: radioSpreadingFactor ?? selfInfo.radioSpreadingFactor,
      radioCodingRate: radioCodingRate ?? selfInfo.radioCodingRate,
      name: name ?? selfInfo.name
    )
  }

  private func patched(pathHashMode: UInt8) -> DeviceCapabilities {
    DeviceCapabilities(
      firmwareVersion: capabilities.firmwareVersion,
      maxContacts: capabilities.maxContacts,
      maxChannels: capabilities.maxChannels,
      blePin: capabilities.blePin,
      firmwareBuild: capabilities.firmwareBuild,
      model: capabilities.model,
      version: capabilities.version,
      clientRepeat: capabilities.clientRepeat,
      pathHashMode: pathHashMode
    )
  }
}
