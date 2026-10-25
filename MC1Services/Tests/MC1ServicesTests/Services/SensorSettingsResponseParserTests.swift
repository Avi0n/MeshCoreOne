import Foundation
@testable import MC1Services
import Testing

@Suite("Sensor settings replies")
struct SensorSettingsResponseParserTests {
  @Test
  func `values remain verbatim across pages`() throws {
    let first = try SensorSettingsResponseParser.list("3 vars\na=1\nb=x=y\n... next:2", offset: 0)
    #expect(first.total == 3)
    #expect(first.next == 2)
    #expect(first.settings == [SensorSetting(key: "a", value: "1"), SensorSetting(key: "b", value: "x=y")])
    let last = try SensorSettingsResponseParser.list("3 vars\nc=", offset: 2)
    #expect(last.settings == [SensorSetting(key: "c", value: "")])
    #expect(last.next == nil)
    #expect(try SensorSettingsResponseParser.value("> x=y ") == "x=y ")
    #expect(try SensorSettingsResponseParser.value("> ") == "")
    #expect(try SensorSettingsResponseParser.value("null") == nil)
    #expect(try SensorSettingsResponseParser.list("no custom var", offset: 0).settings.isEmpty)
    try SensorSettingsResponseParser.accepted("ok")
  }

  @Test(arguments: ["", "0 vars", "-1 vars\na=1", "2 vars\na=1", "1 vars\na=1\na=2",
                    "2 vars\na=1\n... next:0", "2 vars\na=1\n... next:2", "1 vars\n=1", "1 vars\na"])
  func `malformed pages fail`(response: String) {
    #expect(throws: RemoteNodeError.self) { try SensorSettingsResponseParser.list(response, offset: 0) }
  }

  @Test
  func `empty later page and uppercase setter are not success`() {
    #expect(throws: RemoteNodeError.self) { try SensorSettingsResponseParser.list("no custom var", offset: 1) }
    #expect(throws: RemoteNodeError.self) { try SensorSettingsResponseParser.accepted("OK") }
    #expect(throws: RemoteNodeError.self) { try SensorSettingsResponseParser.accepted("can't find custom var") }
  }

  @Test(arguments: ["", "two words", "a\tb", "a\nb", "\0"])
  func `unrepresentable values never become commands`(value: String) {
    #expect(throws: RemoteNodeError.self) { try SensorSetting(key: "gps", value: "0").command(setting: value) }
  }

  @Test
  func `command budget includes correlation prefix in UTF 8`() throws {
    let setting = SensorSetting(key: "a", value: "1")
    let maximum = try setting.command(setting: String(repeating: "x", count: 144))
    #expect(maximum.utf8.count + SensorSetting.correlationPrefixBytes == 160)
    #expect(throws: RemoteNodeError.self) { try setting.command(setting: String(repeating: "x", count: 145)) }
    try SensorSetting.validateCommand(String(repeating: "é", count: 78) + "x")
    #expect(throws: RemoteNodeError.self) { try SensorSetting.validateCommand(String(repeating: "é", count: 79)) }
    #expect(try setting.command(setting: "x=y") == "sensor set a x=y")
  }
}
