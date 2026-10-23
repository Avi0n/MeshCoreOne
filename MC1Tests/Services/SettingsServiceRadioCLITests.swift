import Foundation
@testable import MC1Services
import Testing

@Suite("SettingsService Radio CLI")
struct SettingsServiceRadioCLITests {
  @Test
  func `get returns a value and nil for an unknown key`() async throws {
    let mock = MockConfigurationSession()
    let service = SettingsService(session: mock)

    await mock.setCLIReply("> -7")
    let offset = try await service.getRadioCLIValue("tz.offset")
    #expect(offset == "-7")

    await mock.setCLIReply("Unknown command")
    let missing = try await service.getRadioCLIValue("cad")
    #expect(missing == nil)
    #expect(await mock.runCliCommandCalls == ["get tz.offset", "get cad"])
  }

  @Test
  func `get rejects an error and an unexpected reply`() async {
    let mock = MockConfigurationSession()
    let service = SettingsService(session: mock)

    await mock.setCLIReply("Error, must be 0-2")
    await expectRadioCLIRejected("Error, must be 0-2") {
      try await service.getRadioCLIValue("cad")
    }

    await mock.setCLIReply("OK")
    await expectInvalidResponse {
      try await service.getRadioCLIValue("cad")
    }

    await mock.setCLIReply("plain text")
    await expectInvalidResponse {
      try await service.getRadioCLIValue("cad")
    }
  }

  @Test
  func `set accepts ok and value and rejects error unknown and other`() async throws {
    let mock = MockConfigurationSession()
    let service = SettingsService(session: mock)

    await mock.setCLIReply("OK - interval rounded to 16")
    try await service.setRadioCLIValue("agc.reset.interval", to: "17")

    await mock.setCLIReply("> 16")
    try await service.setRadioCLIValue("agc.reset.interval", to: "17")

    await mock.setCLIReply("ERROR: af must be 0-9")
    await expectRadioCLIRejected("ERROR: af must be 0-9") {
      try await service.setRadioCLIValue("af", to: "9")
    }

    await mock.setCLIReply("Unknown command")
    await expectRadioCLIRejected("Unknown command") {
      try await service.setRadioCLIValue("cad", to: "on")
    }

    await mock.setCLIReply("nope")
    await expectInvalidResponse {
      try await service.setRadioCLIValue("cad", to: "on")
    }
  }

  @Test
  func `set sends an empty value with a trailing space and a nil value with no argument`() async throws {
    let mock = MockConfigurationSession()
    let service = SettingsService(session: mock)
    await mock.setCLIReply("OK")

    try await service.setRadioCLIValue("wifi.pwd", to: "")
    try await service.setRadioCLIValue("wifi.clear", to: nil)

    #expect(await mock.runCliCommandCalls == ["set wifi.pwd ", "set wifi.clear"])
  }

  @Test
  func `wifi reboot pending follows accepted set wifi lines only`() async throws {
    let mock = MockConfigurationSession()
    let service = SettingsService(session: mock)

    await mock.setCLIReply("Error, bad ssid")
    await expectRadioCLIRejected("Error, bad ssid") {
      try await service.setRadioCLIValue("wifi.ssid", to: "net")
    }
    #expect(await service.radioWiFiRebootPending == false)

    await mock.setCLIReply("OK")
    try await service.setRadioCLIValue("wifi.ssid", to: "net")
    #expect(await service.radioWiFiRebootPending == true)

    let passthrough = SettingsService(session: mock)
    let reply = try await passthrough.runCliCommand("set wifi.enabled 0")
    #expect(reply == "OK")
    #expect(await passthrough.radioWiFiRebootPending == true)

    let other = SettingsService(session: mock)
    try await other.setRadioCLIValue("cad", to: "on")
    #expect(await other.radioWiFiRebootPending == false)
  }

  @Test
  func `power off throws when the board stays on`() async {
    let mock = MockConfigurationSession()
    await mock.setPowerOffBehavior(.stillOn)
    let service = SettingsService(session: mock)

    do {
      try await service.powerOff()
      Issue.record("expected powerOffUnsupported")
    } catch let error as SettingsServiceError {
      guard case .powerOffUnsupported = error else {
        Issue.record("expected powerOffUnsupported, got \(error)")
        return
      }
    } catch {
      Issue.record("expected SettingsServiceError, got \(error)")
    }
    #expect(await mock.powerOffCallCount == 1)
  }

  @Test
  func `power off does not treat a dropped link as success`() async {
    let ble = MockConfigurationSession()
    await ble.setPowerOffBehavior(.bleNotConnected)
    await #expect(throws: BLEError.self) {
      try await SettingsService(session: ble).powerOff()
    }

    let wifi = MockConfigurationSession()
    await wifi.setPowerOffBehavior(.wifiNotConnected)
    await #expect(throws: WiFiTransportError.self) {
      try await SettingsService(session: wifi).powerOff()
    }
  }

  @Test
  func `power off wraps other session errors`() async {
    let mock = MockConfigurationSession()
    await mock.setPowerOffBehavior(.invalidInput)
    let service = SettingsService(session: mock)

    do {
      try await service.powerOff()
      Issue.record("expected sessionError")
    } catch let error as SettingsServiceError {
      guard case let .sessionError(inner) = error, case .invalidInput = inner else {
        Issue.record("expected sessionError invalidInput, got \(error)")
        return
      }
    } catch {
      Issue.record("expected SettingsServiceError, got \(error)")
    }
  }

  private func expectRadioCLIRejected(
    _ text: String,
    _ body: () async throws -> Void
  ) async {
    do {
      try await body()
      Issue.record("expected radioCLIRejected")
    } catch let error as SettingsServiceError {
      guard case let .radioCLIRejected(actual) = error else {
        Issue.record("expected radioCLIRejected, got \(error)")
        return
      }
      #expect(actual == text)
    } catch {
      Issue.record("expected SettingsServiceError, got \(error)")
    }
  }

  private func expectInvalidResponse(_ body: () async throws -> Void) async {
    do {
      try await body()
      Issue.record("expected invalidResponse")
    } catch let error as SettingsServiceError {
      guard case .invalidResponse = error else {
        Issue.record("expected invalidResponse, got \(error)")
        return
      }
    } catch {
      Issue.record("expected SettingsServiceError, got \(error)")
    }
  }
}
