import Foundation

/// Default chunk size in bytes for the multi-step signing workflow, matching
/// the concrete session's `sign(_:chunkSize:timeout:)` default.
private let defaultSignChunkSize = 120

/// Session operations for reading and writing device configuration: identity,
/// radio parameters, statistics, custom variables, and key management.
public protocol ConfigurationSessionOps: Actor {
  // MARK: - Device Info

  /// Sends the app-start command and returns the device's self info.
  ///
  /// - Returns: Information about the device itself.
  /// - Throws: `MeshCoreError` if the device doesn't emit `selfInfo`.
  func sendAppStart() async throws -> SelfInfo

  /// Queries the device for its capabilities and system information.
  ///
  /// - Returns: Information about the device hardware, firmware, and supported features.
  /// - Throws: `MeshCoreError` if the device doesn't emit `deviceInfo`.
  func queryDevice() async throws -> DeviceCapabilities

  /// Retrieves the current battery status from the device.
  ///
  /// - Returns: Battery voltage and charge level information.
  /// - Throws: `MeshCoreError` if the device doesn't emit battery info.
  func getBattery() async throws -> BatteryInfo

  // MARK: - Device Configuration

  /// Sets the device's advertised name.
  ///
  /// - Parameter name: The name to advertise (max 32 bytes UTF-8).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setName(_ name: String) async throws

  /// Sets the device's GPS coordinates.
  ///
  /// - Parameters:
  ///   - latitude: Latitude in degrees (-90 to 90).
  ///   - longitude: Longitude in degrees (-180 to 180).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setCoordinates(latitude: Double, longitude: Double) async throws

  /// Sets the radio transmission power level.
  ///
  /// - Parameter power: Power level in dBm (range: -9 to 30).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setTxPower(_ power: Int8) async throws

  /// Configures radio parameters for LoRa communication.
  ///
  /// - Parameters:
  ///   - frequency: Center frequency in MHz (e.g., 915.0).
  ///   - bandwidth: Signal bandwidth in kHz (e.g., 125.0, 250.0, 500.0).
  ///   - spreadingFactor: LoRa spreading factor (7-12, higher = longer range but slower).
  ///   - codingRate: Error correction coding rate (5-8).
  ///   - clientRepeat: Whether to enable client repeat mode (v9+ firmware, omitted if nil).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setRadio(
    frequency: Double,
    bandwidth: Double,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    clientRepeat: Bool?
  ) async throws

  /// Gets the allowed frequency ranges for client repeat mode (v9+ firmware).
  ///
  /// - Returns: The allowed frequency ranges for repeat mode.
  /// - Throws: `MeshCoreError` if the device doesn't emit repeat-frequency data.
  func getRepeatFreq() async throws -> [FrequencyRange]

  /// Sets miscellaneous device parameters.
  ///
  /// - Parameters:
  ///   - manualAddContacts: Whether contacts require manual approval before adding.
  ///   - telemetryModeEnvironment: Environment telemetry reporting mode (0-3).
  ///   - telemetryModeLocation: Location telemetry reporting mode (0-3).
  ///   - telemetryModeBase: Base telemetry reporting mode (0-3).
  ///   - advertisementLocationPolicy: Location inclusion policy for advertisements.
  ///   - multiAcks: Number of acknowledgment retries.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setOtherParams(
    manualAddContacts: Bool,
    telemetryModeEnvironment: UInt8,
    telemetryModeLocation: UInt8,
    telemetryModeBase: UInt8,
    advertisementLocationPolicy: UInt8,
    multiAcks: UInt8?
  ) async throws

  /// Sets the device PIN for administrative access.
  ///
  /// - Parameter pin: 4-digit PIN as a 32-bit unsigned integer.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setDevicePin(_ pin: UInt32) async throws

  /// Gets the current auto-add configuration from the device.
  ///
  /// - Returns: The auto-add configuration (bitmask + max hops).
  /// - Throws: `MeshCoreError` if the device doesn't emit auto-add configuration.
  func getAutoAddConfig() async throws -> AutoAddConfig

  /// Sets the auto-add configuration on the device.
  ///
  /// - Parameter config: The auto-add configuration (bitmask + max hops).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setAutoAddConfig(_ config: AutoAddConfig) async throws

  /// Sets the path hash mode on the device.
  ///
  /// - Parameter mode: Hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setPathHashMode(_ mode: UInt8) async throws

  // MARK: - Default Flood Scope

  /// Persists the device's default flood scope from a ``FloodScope``.
  ///
  /// - Parameters:
  ///   - name: Display name stored on the device.
  ///   - scope: The scope to persist. Passing ``FloodScope/disabled`` clears the scope.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setDefaultFloodScope(name: String, scope: FloodScope) async throws

  /// Fetches the device's persisted default flood scope.
  ///
  /// Requires firmware v11+; older firmware surfaces the unknown opcode as a device error.
  ///
  /// - Returns: The persisted scope, or `nil` if none is configured.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func getDefaultFloodScope() async throws -> DefaultFloodScope?

  // MARK: - Lifecycle

  /// Reboots the device. The session will be disconnected.
  ///
  /// - Throws: `MeshTransportError` if the command cannot be sent.
  func reboot() async throws

  /// Performs a factory reset, erasing all device configuration, contacts, and messages.
  ///
  /// - Warning: This operation is irreversible.
  /// - Throws: `MeshCoreError` on timeout or device error.
  func factoryReset() async throws

  // MARK: - Stats

  /// Retrieves core device statistics.
  ///
  /// - Returns: Core statistics including uptime and system metrics.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func getStatsCore() async throws -> CoreStats

  /// Retrieves radio statistics.
  ///
  /// - Returns: Radio statistics including RSSI, SNR, and transmission counts.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func getStatsRadio() async throws -> RadioStats

  /// Retrieves packet statistics.
  ///
  /// - Returns: Packet statistics including sent, received, and dropped counts.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func getStatsPackets() async throws -> PacketStats

  // MARK: - Custom Variables

  /// Retrieves all custom variables stored on the device.
  ///
  /// - Returns: Dictionary mapping variable names to values.
  /// - Throws: `MeshCoreError` if the device doesn't respond.
  func getCustomVars() async throws -> [String: String]

  /// Sets a custom variable on the device.
  ///
  /// - Parameters:
  ///   - key: Variable name (max 32 bytes).
  ///   - value: Variable value (max 256 bytes).
  /// - Throws: `MeshCoreError` on timeout or device error.
  func setCustomVar(key: String, value: String) async throws

  // MARK: - Key Management and Signing

  /// Exports the device's private key.
  ///
  /// - Returns: The 32-byte private key.
  /// - Throws: `MeshCoreError/featureDisabled` if export is disabled on the device,
  ///   or `MeshCoreError/timeout` if the device doesn't respond.
  func exportPrivateKey() async throws -> Data

  /// Imports a private key into the device, replacing its cryptographic identity.
  ///
  /// - Parameter key: The 64-byte expanded private key to import.
  /// - Throws: `MeshCoreError` if the device rejects or doesn't support key import.
  func importPrivateKey(_ key: Data) async throws

  /// Signs data using the device's private key.
  ///
  /// - Parameters:
  ///   - data: The data to sign.
  ///   - chunkSize: Size of each chunk in bytes.
  ///   - timeout: Optional timeout for the finalization step.
  /// - Returns: The cryptographic signature.
  /// - Throws: `MeshCoreError` if data exceeds device limits or any step times out.
  func sign(_ data: Data, chunkSize: Int, timeout: TimeInterval?) async throws -> Data
}

// MARK: - Default Implementations

public extension ConfigurationSessionOps {
  /// Signs data using the default chunk size and the session's default timeout.
  func sign(_ data: Data) async throws -> Data {
    try await sign(data, chunkSize: defaultSignChunkSize, timeout: nil)
  }
}
