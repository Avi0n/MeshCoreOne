import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("NodeSettingsViewModel identity validation")
@MainActor
struct NodeSettingsIdentityValidationTests {
  // MARK: - In-range passes

  @Test
  func `in range coordinates produce no errors`() {
    let errors = NodeSettingsViewModel.validateIdentityFields(
      name: "Repeater One", latitude: 37.7749, longitude: -122.4194
    )
    #expect(errors.name == nil)
    #expect(errors.latitude == nil)
    #expect(errors.longitude == nil)
    #expect(errors.hasErrors == false)
  }

  @Test
  func `zero coordinates are valid`() {
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: 0, longitude: 0)
    #expect(errors.hasErrors == false)
  }

  @Test
  func `nil fields are skipped not flagged`() {
    // nil means the field was never loaded/edited; the apply path skips it, so validation must not flag it.
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: nil, longitude: nil)
    #expect(errors.hasErrors == false)
  }

  // MARK: - Inclusive boundaries pass

  @Test(arguments: [-90.0, 90.0])
  func `latitude boundaries are inclusive`(lat: Double) {
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: lat, longitude: 0)
    #expect(errors.latitude == nil, "Latitude \(lat) is a valid boundary and must pass")
  }

  @Test(arguments: [-180.0, 180.0])
  func `longitude boundaries are inclusive`(lon: Double) {
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: 0, longitude: lon)
    #expect(errors.longitude == nil, "Longitude \(lon) is a valid boundary and must pass")
  }

  // MARK: - Out-of-range rejected

  @Test(arguments: [-90.0001, 90.0001, -91, 91, 322.2, -1000])
  func `out of range latitude is rejected`(lat: Double) {
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: lat, longitude: 0)
    #expect(errors.latitude != nil, "Latitude \(lat) is out of range and must be flagged")
    #expect(errors.longitude == nil, "In-range longitude must stay valid")
  }

  @Test(arguments: [-180.0001, 180.0001, -181, 181, 5000, -5000])
  func `out of range longitude is rejected`(lon: Double) {
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: 0, longitude: lon)
    #expect(errors.longitude != nil, "Longitude \(lon) is out of range and must be flagged")
    #expect(errors.latitude == nil, "In-range latitude must stay valid")
  }

  @Test
  func `ranges are not swapped`() {
    // 150 is a legal longitude but an illegal latitude; a swapped-range bug would pass this.
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: 150, longitude: 150)
    #expect(errors.latitude != nil, "150 exceeds the latitude range")
    #expect(errors.longitude == nil, "150 is within the longitude range")
  }

  // MARK: - Non-finite rejected

  @Test(arguments: [Double.nan, .infinity, -.infinity])
  func `non finite latitude is rejected`(lat: Double) {
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: lat, longitude: 0)
    #expect(errors.latitude != nil, "Non-finite latitude \(lat) must be flagged")
  }

  @Test(arguments: [Double.nan, .infinity, -.infinity])
  func `non finite longitude is rejected`(lon: Double) {
    let errors = NodeSettingsViewModel.validateIdentityFields(name: nil, latitude: 0, longitude: lon)
    #expect(errors.longitude != nil, "Non-finite longitude \(lon) must be flagged")
  }

  // MARK: - Name length

  @Test
  func `name at byte cap is valid`() {
    let name = String(repeating: "a", count: ProtocolLimits.maxUsableNameBytes)
    let errors = NodeSettingsViewModel.validateIdentityFields(name: name, latitude: nil, longitude: nil)
    #expect(errors.name == nil, "A name at the byte cap must pass")
  }

  @Test
  func `name over byte cap is rejected`() {
    let name = String(repeating: "a", count: ProtocolLimits.maxUsableNameBytes + 1)
    let errors = NodeSettingsViewModel.validateIdentityFields(name: name, latitude: nil, longitude: nil)
    #expect(errors.name != nil, "A name over the byte cap must be flagged")
  }

  @Test
  func `name byte cap counts UTF 8 bytes not characters`() {
    // Each emoji is 4 UTF-8 bytes, so 8 of them exceed the 31-byte cap despite being 8 characters.
    let name = String(repeating: "😀", count: 8)
    let errors = NodeSettingsViewModel.validateIdentityFields(name: name, latitude: nil, longitude: nil)
    #expect(errors.name != nil, "Name length must be measured in UTF-8 bytes")
  }

  @Test
  func `all three invalid reports all three`() {
    let name = String(repeating: "a", count: ProtocolLimits.maxUsableNameBytes + 1)
    let errors = NodeSettingsViewModel.validateIdentityFields(name: name, latitude: 200, longitude: 400)
    #expect(errors.name != nil)
    #expect(errors.latitude != nil)
    #expect(errors.longitude != nil)
    #expect(errors.hasErrors)
  }
}

@Suite("NodeSettingsViewModel identity apply guard")
@MainActor
struct NodeSettingsIdentityApplyGuardTests {
  /// Records every CLI command the view model emits and replies "OK" to each.
  @MainActor
  final class CommandRecorder {
    private(set) var commands: [String] = []
    func send(_ id: UUID, _ command: String, _ timeout: Duration) async throws -> String {
      commands.append(command)
      return "OK"
    }
  }

  private func makeConfiguredViewModel(recorder: CommandRecorder) -> NodeSettingsViewModel {
    let session = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test Node",
      role: .repeater,
      isConnected: true,
      permissionLevel: .admin
    )
    let viewModel = NodeSettingsViewModel()
    viewModel.configure(session: session, sendCommand: recorder.send, sendRawCommand: recorder.send)
    return viewModel
  }

  @Test
  func `out of range latitude blocks set lat`() async {
    let recorder = CommandRecorder()
    let viewModel = makeConfiguredViewModel(recorder: recorder)
    viewModel.latitude = 999

    await viewModel.applyIdentitySettings()

    #expect(recorder.commands.isEmpty, "No command may be sent when a field is out of range")
    #expect(viewModel.latitudeError != nil, "The out-of-range field must be flagged inline")
    #expect(viewModel.identityApplySuccess == false)
  }

  @Test
  func `out of range longitude blocks set lon`() async {
    let recorder = CommandRecorder()
    let viewModel = makeConfiguredViewModel(recorder: recorder)
    viewModel.longitude = -400

    await viewModel.applyIdentitySettings()

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.longitudeError != nil)
  }

  @Test
  func `over long name blocks set name`() async {
    let recorder = CommandRecorder()
    let viewModel = makeConfiguredViewModel(recorder: recorder)
    viewModel.name = String(repeating: "a", count: ProtocolLimits.maxUsableNameBytes + 1)

    await viewModel.applyIdentitySettings()

    #expect(recorder.commands.isEmpty)
    #expect(viewModel.nameError != nil)
  }

  @Test
  func `valid coordinates are sent over the transport`() async {
    let recorder = CommandRecorder()
    let viewModel = makeConfiguredViewModel(recorder: recorder)
    viewModel.latitude = 37.7749
    viewModel.longitude = -122.4194

    await viewModel.applyIdentitySettings()

    #expect(recorder.commands.contains { $0.hasPrefix("set lat 37.7749") })
    #expect(recorder.commands.contains { $0.hasPrefix("set lon -122.4194") })
    #expect(viewModel.latitudeError == nil)
    #expect(viewModel.longitudeError == nil)
  }

  @Test
  func `reapply clears stale errors once corrected`() async {
    let recorder = CommandRecorder()
    let viewModel = makeConfiguredViewModel(recorder: recorder)

    viewModel.latitude = 999
    await viewModel.applyIdentitySettings()
    #expect(viewModel.latitudeError != nil)

    viewModel.latitude = 45
    await viewModel.applyIdentitySettings()
    #expect(viewModel.latitudeError == nil, "Correcting the value must clear the stale inline error")
    #expect(recorder.commands.contains { $0.hasPrefix("set lat 45") })
  }
}

@Suite("NodeSettingsViewModel radio apply")
@MainActor
struct NodeSettingsRadioApplyTests {
  @MainActor
  final class CommandRecorder {
    private(set) var commands: [String] = []
    func send(_ id: UUID, _ command: String, _ timeout: Duration) async throws -> String {
      commands.append(command)
      return "OK"
    }
  }

  private func makeConfiguredViewModel(recorder: CommandRecorder) -> NodeSettingsViewModel {
    let session = RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: 32),
      name: "Test Node",
      role: .repeater,
      isConnected: true,
      permissionLevel: .admin
    )
    let viewModel = NodeSettingsViewModel()
    viewModel.configure(session: session, sendCommand: recorder.send, sendRawCommand: recorder.send)
    return viewModel
  }

  @Test
  func `apply radio settings sends only set radio and clears modified flag`() async {
    let recorder = CommandRecorder()
    let viewModel = makeConfiguredViewModel(recorder: recorder)
    viewModel.frequency = 915.0
    viewModel.bandwidth = 250.0
    viewModel.spreadingFactor = 10
    viewModel.codingRate = 5
    viewModel.radioSettingsModified = true

    await viewModel.applyRadioSettings()

    #expect(recorder.commands == ["set radio 915.0,250.0,10,5"])
    #expect(recorder.commands.contains { $0.contains("set tx") } == false)
    #expect(viewModel.radioSettingsModified == false)
  }

  @Test
  func `fetch radio settings sends only get radio`() async {
    let recorder = CommandRecorder()
    let viewModel = makeConfiguredViewModel(recorder: recorder)

    await viewModel.fetchRadioSettings()

    #expect(recorder.commands == ["get radio"])
  }
}
