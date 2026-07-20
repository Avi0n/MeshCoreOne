import Foundation
@testable import MC1Services
import Testing

@Suite("CLIResponse parsing")
struct CLIResponseTests {
  // MARK: - TX Power

  @Test
  func `Bare integer parses as TX power`() {
    #expect(CLIResponse.parse("> 22", forQuery: "get tx") == .txPower(22))
    #expect(CLIResponse.parse("-5", forQuery: "get tx") == .txPower(-5))
  }

  @Test
  func `ZephCore adaptive power annotation reads the max ceiling`() {
    #expect(CLIResponse.parse("> 22dBm (apc=off)", forQuery: "get tx") == .txPower(22))
    #expect(CLIResponse.parse(
      "> 16dBm (apc=on max=22 reduction=6 margin=18.5 target=16)",
      forQuery: "get tx"
    ) == .txPower(22))
  }

  @Test
  func `Radio CSV never parses as TX power`() {
    // A "get radio" reply misattributed to "get tx" once showed 910 dBm for a
    // 910.525 MHz repeater; the leading integer of a decimal or CSV must not match.
    #expect(CLIResponse.parse("> 910.525,62.500,7,7", forQuery: "get tx") == .raw("910.525,62.500,7,7"))
    #expect(CLIResponse.parse("910.5", forQuery: "get tx") == .raw("910.5"))
  }

  // MARK: - Radio

  @Test
  func `Radio CSV parses only for the radio query`() {
    #expect(CLIResponse.parse("> 915.000,250.0,10,5", forQuery: "get radio")
      == .radio(frequency: 915.0, bandwidth: 250.0, spreadingFactor: 10, codingRate: 5))
    #expect(CLIResponse.parse("> 22", forQuery: "get radio") == .raw("22"))
  }

  // MARK: - Device Time

  @Test
  func `Clock reply parses as device time only for the clock query`() {
    #expect(CLIResponse.parse("06:40 - 18/4/2025 UTC", forQuery: "clock")
      == .deviceTime("06:40 - 18/4/2025 UTC"))
    // The ":" + "/" shape also appears in free-form text; without the clock
    // query it must stay raw instead of being adopted as a timestamp.
    #expect(CLIResponse.parse("Contact: KD7ABC / 145.230") == .raw("Contact: KD7ABC / 145.230"))
    #expect(CLIResponse.parse("06:40 - 18/4/2025 UTC", forQuery: "get radio")
      == .raw("06:40 - 18/4/2025 UTC"))
  }

  // MARK: - Response Matching

  @Test
  func `Structured get queries reject replies of the wrong shape`() {
    #expect(!CLIResponse.isPlausibleResponse("> 910.525,62.500,7,7", forQuery: "get tx"))
    #expect(!CLIResponse.isPlausibleResponse("> 22", forQuery: "get radio"))
    #expect(!CLIResponse.isPlausibleResponse("Alpha Repeater", forQuery: "get lat"))
    #expect(!CLIResponse.isPlausibleResponse("> 22", forQuery: "clock"))
  }

  @Test
  func `Structured get queries accept their own shape and errors`() {
    #expect(CLIResponse.isPlausibleResponse("> 22", forQuery: "get tx"))
    #expect(CLIResponse.isPlausibleResponse("> 915.000,250.0,10,5", forQuery: "get radio"))
    #expect(CLIResponse.isPlausibleResponse("-36.8485", forQuery: "get lat"))
    #expect(CLIResponse.isPlausibleResponse("ERR: not allowed", forQuery: "get tx"))
  }

  @Test
  func `Free-form and action commands accept any reply`() {
    // Firmware success replies are not uniformly "OK"-prefixed.
    #expect(CLIResponse.isPlausibleResponse("password now: hunter2", forQuery: "password hunter2"))
    #expect(CLIResponse.isPlausibleResponse("OK", forQuery: "set tx 22"))
    #expect(CLIResponse.isPlausibleResponse("Alpha Repeater", forQuery: "get name"))
    #expect(CLIResponse.isPlausibleResponse("regions saved", forQuery: "region save"))
  }

  @Test
  func `Echoed wire prefix splits into prefix and body`() {
    let split = CLIResponse.splitEchoedPrefix("3A|> 22dBm (apc=off)")
    #expect(split?.prefix == "3A|")
    #expect(split?.body == "> 22dBm (apc=off)")
  }

  @Test
  func `Multi-line body survives prefix splitting`() {
    let split = CLIResponse.splitEchoedPrefix("0F|US/CA^\n  local F")
    #expect(split?.body == "US/CA^\n  local F")
  }

  @Test
  func `Ordinary reply text is not mistaken for a wire prefix`() {
    // Only two uppercase hex digits plus the separator qualify.
    #expect(CLIResponse.splitEchoedPrefix("> 22dBm (apc=off)") == nil)
    #expect(CLIResponse.splitEchoedPrefix("3a|lowercase") == nil)
    #expect(CLIResponse.splitEchoedPrefix("no|t hex") == nil)
    #expect(CLIResponse.splitEchoedPrefix("FF|") == nil)
    #expect(CLIResponse.splitEchoedPrefix("") == nil)
  }
}
