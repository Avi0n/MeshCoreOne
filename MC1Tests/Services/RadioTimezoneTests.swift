import Foundation
@testable import MC1Services
import Testing

@Suite("Radio timezone")
@MainActor
struct RadioTimezoneTests {
  @Test
  func `offset hours keeps whole hours inside the radio range`() {
    #expect(RadioTimezone.offsetHours(secondsFromGMT: 0) == 0)
    #expect(RadioTimezone.offsetHours(secondsFromGMT: -25200) == -7)
    #expect(RadioTimezone.offsetHours(secondsFromGMT: 50400) == 14)
    #expect(RadioTimezone.offsetHours(secondsFromGMT: -43200) == -12)
    #expect(RadioTimezone.offsetHours(secondsFromGMT: 19800) == nil)
    #expect(RadioTimezone.offsetHours(secondsFromGMT: 46800) == 13)
    #expect(RadioTimezone.offsetHours(secondsFromGMT: -46800) == nil)
  }

  @Test
  func `sync writes when the stored offset differs`() async {
    let mock = MockConfigurationSession()
    await mock.setCLIReply("> 0")
    let settings = SettingsService(session: mock)

    await RadioTimezone.syncIfNeeded(
      supportsRadioCLI: true,
      secondsFromGMT: -25200,
      settings: settings,
      stillCurrent: { true }
    )

    #expect(await mock.runCliCommandCalls == ["get tz.offset", "set tz.offset -7"])
  }

  @Test
  func `sync skips the write when the radio already matches`() async {
    let mock = MockConfigurationSession()
    await mock.setCLIReply("> -7")
    let settings = SettingsService(session: mock)

    await RadioTimezone.syncIfNeeded(
      supportsRadioCLI: true,
      secondsFromGMT: -25200,
      settings: settings,
      stillCurrent: { true }
    )

    #expect(await mock.runCliCommandCalls == ["get tz.offset"])
  }

  @Test
  func `sync sends nothing for a fractional offset`() async {
    let mock = MockConfigurationSession()
    let settings = SettingsService(session: mock)

    await RadioTimezone.syncIfNeeded(
      supportsRadioCLI: true,
      secondsFromGMT: 19800,
      settings: settings,
      stillCurrent: { true }
    )

    #expect(await mock.runCliCommandCalls.isEmpty)
  }

  @Test
  func `sync sends nothing when the radio has no radio CLI`() async {
    let mock = MockConfigurationSession()
    let settings = SettingsService(session: mock)

    await RadioTimezone.syncIfNeeded(
      supportsRadioCLI: false,
      secondsFromGMT: -25200,
      settings: settings,
      stillCurrent: { true }
    )

    #expect(await mock.runCliCommandCalls.isEmpty)
  }

  @Test
  func `sync returns after an error reply without writing`() async {
    let mock = MockConfigurationSession()
    await mock.setCLIReply("Error: bad offset")
    let settings = SettingsService(session: mock)

    await RadioTimezone.syncIfNeeded(
      supportsRadioCLI: true,
      secondsFromGMT: -25200,
      settings: settings,
      stillCurrent: { true }
    )

    #expect(await mock.runCliCommandCalls == ["get tz.offset"])
  }

  @Test
  func `sync skips the write when the connection changed during the read`() async {
    let mock = MockConfigurationSession()
    await mock.setCLIReply("> 0")
    let settings = SettingsService(session: mock)

    await RadioTimezone.syncIfNeeded(
      supportsRadioCLI: true,
      secondsFromGMT: -25200,
      settings: settings,
      stillCurrent: { false }
    )

    #expect(await mock.runCliCommandCalls == ["get tz.offset"])
  }
}
