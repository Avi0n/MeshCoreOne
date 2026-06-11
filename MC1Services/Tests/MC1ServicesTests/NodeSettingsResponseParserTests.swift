import Foundation
import Testing
@testable import MC1Services

@Suite("NodeSettingsResponseParser")
struct NodeSettingsResponseParserTests {

    // MARK: - Settings Field Matching

    @Test("Radio response matches the radio field through the prompt prefix")
    func radioResponse() {
        let value = NodeSettingsResponseParser.firstSettingsValue(
            in: "> 915.000,250.0,10,5",
            checking: [.radio, .txPower]
        )
        #expect(value == .radio(frequency: 915.0, bandwidth: 250.0, spreadingFactor: 10, codingRate: 5))
    }

    @Test("Integer response matches TX power")
    func txPowerResponse() {
        let value = NodeSettingsResponseParser.firstSettingsValue(in: "22", checking: [.txPower])
        #expect(value == .txPower(22))
    }

    @Test("Version response matches firmware version with and without MeshCore prefix")
    func versionResponse() {
        let prefixed = NodeSettingsResponseParser.firstSettingsValue(
            in: "MeshCore v1.11.0 (2025-04-18)",
            checking: [.firmwareVersion]
        )
        #expect(prefixed == .firmwareVersion("MeshCore v1.11.0 (2025-04-18)"))

        let bare = NodeSettingsResponseParser.firstSettingsValue(
            in: "v1.10.0 (2025-03-02)",
            checking: [.firmwareVersion]
        )
        #expect(bare == .firmwareVersion("v1.10.0 (2025-03-02)"))
    }

    @Test("Clock response matches device time")
    func deviceTimeResponse() {
        let value = NodeSettingsResponseParser.firstSettingsValue(
            in: "06:40 - 18/4/2025 UTC",
            checking: [.deviceTime]
        )
        #expect(value == .deviceTime("06:40 - 18/4/2025 UTC"))
    }

    @Test("Numeric responses match latitude and longitude")
    func coordinateResponses() {
        let lat = NodeSettingsResponseParser.firstSettingsValue(in: "-36.8485", checking: [.latitude])
        #expect(lat == .latitude(-36.8485))

        let lon = NodeSettingsResponseParser.firstSettingsValue(in: "174.7633", checking: [.longitude])
        #expect(lon == .longitude(174.7633))
    }

    @Test("Free-form text falls through numeric fields to name")
    func nameOrderingAfterCoordinates() {
        let value = NodeSettingsResponseParser.firstSettingsValue(
            in: "Alpha Repeater",
            checking: [.latitude, .longitude, .name]
        )
        #expect(value == .name("Alpha Repeater"))
    }

    @Test("A bare number is captured by latitude before name")
    func numericCaptureOrdering() {
        let value = NodeSettingsResponseParser.firstSettingsValue(
            in: "12.5",
            checking: [.latitude, .name]
        )
        #expect(value == .latitude(12.5))
    }

    @Test("Owner info keeps the wire pipe separator")
    func ownerInfoResponse() {
        let value = NodeSettingsResponseParser.firstSettingsValue(
            in: "Contact: KD7ABC|ch 31",
            checking: [.ownerInfo]
        )
        #expect(value == .ownerInfo("Contact: KD7ABC|ch 31"))
    }

    @Test("OK and error responses match no settings field")
    func okAndErrorResponses() {
        let fields: [NodeSettingsResponseParser.SettingsField] = [
            .radio, .txPower, .firmwareVersion, .latitude, .longitude, .name, .ownerInfo,
        ]
        #expect(NodeSettingsResponseParser.firstSettingsValue(in: "OK", checking: fields) == nil)
        #expect(NodeSettingsResponseParser.firstSettingsValue(in: "ERR: no such setting", checking: fields) == nil)
    }

    @Test("Empty field list never matches")
    func emptyFieldList() {
        #expect(NodeSettingsResponseParser.firstSettingsValue(in: "22", checking: []) == nil)
    }

    // MARK: - Behavior Fields

    @Test("Integer fills the first missing behavior field in fixed order")
    func behaviorOrdering() {
        let advert = NodeSettingsResponseParser.behaviorLateResponse(
            "90", hasAdvertInterval: false, hasFloodInterval: false, hasFloodMaxHops: false
        )
        #expect(advert == .advertInterval(90))

        let flood = NodeSettingsResponseParser.behaviorLateResponse(
            "24", hasAdvertInterval: true, hasFloodInterval: false, hasFloodMaxHops: false
        )
        #expect(flood == .floodAdvertInterval(24))

        let hops = NodeSettingsResponseParser.behaviorLateResponse(
            "8", hasAdvertInterval: true, hasFloodInterval: true, hasFloodMaxHops: false
        )
        #expect(hops == .floodMax(8))
    }

    @Test("Behavior matching returns nil when all fields are present or text is non-numeric")
    func behaviorNoMatch() {
        #expect(NodeSettingsResponseParser.behaviorLateResponse(
            "90", hasAdvertInterval: true, hasFloodInterval: true, hasFloodMaxHops: true
        ) == nil)
        #expect(NodeSettingsResponseParser.behaviorLateResponse(
            "Alpha Repeater", hasAdvertInterval: false, hasFloodInterval: false, hasFloodMaxHops: false
        ) == nil)
    }

    // MARK: - Device Clock

    @Test("Firmware clock response parses to the exact UTC date")
    func clockResponseParses() throws {
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

    @Test("Non-clock text returns nil")
    func clockResponseRejectsOtherText() {
        #expect(NodeSettingsResponseParser.utcDate(fromClockResponse: "Alpha Repeater") == nil)
        #expect(NodeSettingsResponseParser.utcDate(fromClockResponse: "06:40 - 18/4/2025") == nil)
    }

    // MARK: - Clock Sync

    @Test("Clock sync outcomes classify OK, clock-ahead, generic error, and unexpected text")
    func clockSyncClassification() {
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

    @Test("Password change succeeds on OK or the firmware echo, fails otherwise")
    func passwordClassification() {
        #expect(NodeSettingsResponseParser.isPasswordChangeSuccessful("> password now: hunter2"))
        #expect(NodeSettingsResponseParser.isPasswordChangeSuccessful("OK"))
        #expect(!NodeSettingsResponseParser.isPasswordChangeSuccessful("ERR: bad password"))
        #expect(!NodeSettingsResponseParser.isPasswordChangeSuccessful("Alpha Repeater"))
    }

    // MARK: - Owner Info

    @Test("Owner info wire and display forms round-trip")
    func ownerInfoMapping() {
        #expect(NodeSettingsResponseParser.displayOwnerInfo(fromWire: "KD7ABC|ch 31") == "KD7ABC\nch 31")
        #expect(NodeSettingsResponseParser.wireOwnerInfo(fromDisplay: "KD7ABC\nch 31") == "KD7ABC|ch 31")
        let display = "line one\nline two\nline three"
        #expect(NodeSettingsResponseParser.displayOwnerInfo(
            fromWire: NodeSettingsResponseParser.wireOwnerInfo(fromDisplay: display)
        ) == display)
    }
}
