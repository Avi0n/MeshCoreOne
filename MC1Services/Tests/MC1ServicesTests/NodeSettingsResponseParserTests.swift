import Foundation
@testable import MC1Services
import Testing

@Suite("NodeSettingsResponseParser")
struct NodeSettingsResponseParserTests {
  // MARK: - Late Reply Recovery

  @Test
  func `late reply recovers for the single unanswered query it parses for`() {
    let recovered = NodeSettingsResponseParser.recoveredResponse(
      "> 22", unansweredQueries: ["get tx"]
    )
    #expect(recovered?.query == "get tx")
    #expect(recovered?.value == .txPower(22))

    let radio = NodeSettingsResponseParser.recoveredResponse(
      "> 915.000,250.0,10,5", unansweredQueries: ["get tx", "get radio"]
    )
    #expect(radio?.query == "get radio")
    #expect(radio?.value == .radio(frequency: 915.0, bandwidth: 250.0, spreadingFactor: 10, codingRate: 5))
  }

  @Test
  func `a bare double is ambiguous when both coordinates are unanswered`() {
    #expect(NodeSettingsResponseParser.recoveredResponse(
      "38.5", unansweredQueries: ["get lat", "get lon"]
    ) == nil)

    let single = NodeSettingsResponseParser.recoveredResponse(
      "38.5", unansweredQueries: ["get lon"]
    )
    #expect(single?.value == .longitude(38.5))
  }

  @Test
  func `query-independent shapes are never recovered`() {
    for response in ["OK", "ERR: not allowed", "MeshCore v1.11.0 (2025-04-18)"] {
      #expect(NodeSettingsResponseParser.recoveredResponse(
        response, unansweredQueries: ["get tx"]
      ) == nil)
    }
  }

  @Test
  func `radio CSV never recovers as TX power`() {
    #expect(NodeSettingsResponseParser.recoveredResponse(
      "> 910.525,62.500,7,7", unansweredQueries: ["get tx"]
    ) == nil)
  }

  @Test
  func `empty and free-form query sets recover nothing`() {
    #expect(NodeSettingsResponseParser.recoveredResponse("22", unansweredQueries: []) == nil)
    #expect(NodeSettingsResponseParser.recoveredResponse(
      "Alpha Repeater", unansweredQueries: ["get name"]
    ) == nil)
  }

  // MARK: - Device Clock

  @Test
  func `Firmware clock response parses to the exact UTC date`() throws {
    let date = try #require(NodeSettingsResponseParser.utcDate(fromClockResponse: "06:40 - 18/4/2025 UTC"))

    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = try #require(TimeZone(identifier: "UTC"))
    let components = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
    #expect(components.year == 2025)
    #expect(components.month == 4)
    #expect(components.day == 18)
    #expect(components.hour == 6)
    #expect(components.minute == 40)
  }

  @Test
  func `Non-clock text returns nil`() {
    #expect(NodeSettingsResponseParser.utcDate(fromClockResponse: "Alpha Repeater") == nil)
    #expect(NodeSettingsResponseParser.utcDate(fromClockResponse: "06:40 - 18/4/2025") == nil)
  }

  // MARK: - Clock Sync

  @Test
  func `Clock sync outcomes classify OK, clock-ahead, generic error, and unexpected text`() {
    #expect(NodeSettingsResponseParser.classifyClockSyncResponse("OK - clock set") == .synced)
    #expect(NodeSettingsResponseParser.classifyClockSyncResponse(
      "ERR: clock cannot go backwards"
    ) == .clockAhead)
    #expect(NodeSettingsResponseParser.classifyClockSyncResponse(
      "ERR: invalid time"
    ) == .failed(message: "invalid time"))
    #expect(NodeSettingsResponseParser.classifyClockSyncResponse("hello") == .unexpected)
  }

  // MARK: - Password

  @Test
  func `Password change succeeds on OK or the firmware echo, fails otherwise`() {
    #expect(NodeSettingsResponseParser.isPasswordChangeSuccessful("> password now: hunter2"))
    #expect(NodeSettingsResponseParser.isPasswordChangeSuccessful("OK"))
    #expect(!NodeSettingsResponseParser.isPasswordChangeSuccessful("ERR: bad password"))
    #expect(!NodeSettingsResponseParser.isPasswordChangeSuccessful("Alpha Repeater"))
  }

  // MARK: - Owner Info

  @Test
  func `Owner info wire and display forms round-trip`() {
    #expect(NodeSettingsResponseParser.displayOwnerInfo(fromWire: "KD7ABC|ch 31") == "KD7ABC\nch 31")
    #expect(NodeSettingsResponseParser.wireOwnerInfo(fromDisplay: "KD7ABC\nch 31") == "KD7ABC|ch 31")
    let display = "line one\nline two\nline three"
    #expect(NodeSettingsResponseParser.displayOwnerInfo(
      fromWire: NodeSettingsResponseParser.wireOwnerInfo(fromDisplay: display)
    ) == display)
  }
}
