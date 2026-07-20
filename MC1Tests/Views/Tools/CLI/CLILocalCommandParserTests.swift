@testable import MC1
import Testing

@Suite("CLILocalCommandParser")
struct CLILocalCommandParserTests {
  // MARK: - Happy paths

  @Test(arguments: [
    ("clock", CLILocalCommand.clock),
    ("CLOCK", .clock),
    ("clock sync", .clockSync),
    ("clock SYNC", .clockSync),
    ("ver", .ver),
    ("board", .board),
    ("advert", .advert(flood: false)),
    ("advert.zerohop", .advert(flood: false)),
    ("floodadv", .advert(flood: true)),
    ("reboot", .reboot),
    ("get name", .getKey(.name)),
    ("get bat", .getKey(.bat)),
    ("get public.key", .getKey(.publicKey)),
    ("get multi.acks", .getKey(.multiAcks)),
    ("get path.hash.mode", .getKey(.pathHashMode)),
    ("GET RADIO", .getKey(.radio)),
    ("set name Field Node 3", .setName("Field Node 3")),
    ("set tx 22", .setTxPower(22)),
    ("set tx -9", .setTxPower(-9)),
    ("set lat 47.49", .setLatitude(47.49)),
    ("set lon -120.33", .setLongitude(-120.33)),
    ("set freq 869.525", .setFrequency(869.525)),
    ("set multi.acks 1", .setMultiAcks(1)),
    ("set path.hash.mode 2", .setPathHashMode(2)),
    ("set radio 869.525,250,11,5", .setRadio(frequencyMHz: 869.525, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5)),
    ("set radio 869.525, 250, 11, 5", .setRadio(frequencyMHz: 869.525, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5)),
  ])
  func `parses valid commands`(line: String, expected: CLILocalCommand) {
    #expect(CLILocalCommandParser.parse(line) == .command(expected))
  }

  @Test
  func `preserves name case and inner spaces`() {
    #expect(CLILocalCommandParser.parse("set name  Mixed CASE  ") == .command(.setName("Mixed CASE")))
  }

  // MARK: - Fall-through (not a local command)

  @Test(arguments: ["", "   ", "help", "clear", "session list", "login node", "logout", "nodes", "channels", "wat"])
  func `unrecognized first words fall through`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .notLocal)
  }

  // MARK: - Confirmation tier

  @Test
  func `dangerous commands require confirmation`() {
    #expect(CLILocalCommand.reboot.requiresConfirmation)
    #expect(CLILocalCommand.setFrequency(869.5).requiresConfirmation)
    #expect(CLILocalCommand.setRadio(frequencyMHz: 869.5, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5).requiresConfirmation)
    #expect(!CLILocalCommand.setTxPower(22).requiresConfirmation)
    #expect(!CLILocalCommand.clock.requiresConfirmation)
  }

  // MARK: - Error paths

  @Test(arguments: [
    "get",
    "get   ",
  ])
  func `get without a key is bad arguments`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.badArguments(.get)))
  }

  @Test(arguments: ["set", "set name", "set name    "])
  func `set without value is bad arguments`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.badArguments(.set)))
  }

  @Test(arguments: ["set public.key abcd", "set bat 42"])
  func `read-only keys are not settable`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.badArguments(.set)))
  }

  @Test(arguments: ["set tx abc", "set lat notanumber", "set freq xyz"])
  func `non-numeric set values are bad arguments`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.badArguments(.set)))
  }

  @Test(arguments: [
    "set tx 200", // exceeds Int8
    "set lat 200", // > 90
    "set lon -300", // < -180
    "set freq 3000", // > 2500 MHz
    "set multi.acks 5", // > 1
    "set path.hash.mode 9", // > 2
  ])
  func `out-of-range set values are reported`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.valueOutOfRange))
  }

  @Test(arguments: ["set radio 869.525", "set radio 869.525,250,11", "set radio a,b,c,d"])
  func `malformed set radio is bad arguments`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.badArguments(.setRadio)))
  }

  @Test(arguments: [
    "set radio 3000,250,11,5", // freq out of range
    "set radio 869.525,600,11,5", // bw out of range
    "set radio 869.525,250,3,5", // sf < 5
    "set radio 869.525,250,11,9", // cr > 8
  ])
  func `out-of-range set radio fields are reported`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.valueOutOfRange))
  }

  // MARK: - Custom-var fallthrough (bare get/set)

  @Test(arguments: [
    ("get custom", CLILocalCommand.getCustomVars),
    ("GET CUSTOM", .getCustomVars),
    ("get gps", .getCustomVar("gps")),
    ("get GPS", .getCustomVar("GPS")), // key kept verbatim (firmware is case-sensitive)
    ("get gps extra", .getCustomVar("gps")), // first-token semantics
    ("get _tx", .getCustomVar("tx")), // leading `_` forces custom routing past typed `tx`
    ("get _custom", .getCustomVar("custom")), // `_` reaches a var named like the dump verb
    ("get __x", .getCustomVar("_x")), // only one leading `_` is stripped
  ])
  func `bare get falls through to a custom var`(line: String, expected: CLILocalCommand) {
    #expect(CLILocalCommandParser.parse(line) == .command(expected))
  }

  @Test(arguments: [
    ("set gps 1", CLILocalCommand.setCustomVar(key: "gps", value: "1")),
    ("set gps_interval 60", .setCustomVar(key: "gps_interval", value: "60")),
    ("set label Field Node 3", .setCustomVar(key: "label", value: "Field Node 3")), // inner spaces survive
    ("set url a:b", .setCustomVar(key: "url", value: "a:b")), // ':' allowed in a value
    ("set wifi_ssid MyNetwork", .setCustomVar(key: "wifi_ssid", value: "MyNetwork")),
    ("set wifi_ssid -", .setCustomVar(key: "wifi_ssid", value: "-")), // firmware-side clear convention
    ("set _tx 22", .setCustomVar(key: "tx", value: "22")), // `_` forces custom routing past typed `tx`
  ])
  func `bare set falls through to a custom var`(line: String, expected: CLILocalCommand) {
    #expect(CLILocalCommandParser.parse(line) == .command(expected))
  }

  @Test
  func `typed keys keep priority over custom-var fallthrough`() {
    #expect(CLILocalCommandParser.parse("get tx") == .command(.getKey(.tx)))
    #expect(CLILocalCommandParser.parse("set TX 22") == .command(.setTxPower(22)))
  }

  @Test(arguments: [
    "set a,b 1", // ',' in key
    "set a:b 1", // ':' in key
    "set gps 1,2", // ',' in value fragments the get-all decode
    "get bad:key", // ':' in key
    "get bad,key", // ',' in key
  ])
  func `structurally invalid custom-var tokens are reported`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .invalid(.invalidCustomVarToken))
  }

  @Test
  func `oversized custom-var set pair is rejected by byte length`() {
    // key(1) + ":" (1) + value counts UTF-8 bytes, not characters: "é" is 2 bytes.
    let overValue = "a" + String(repeating: "é", count: 69) // 1 + 138 = 139 bytes
    #expect("k:\(overValue)".utf8.count == 141)
    #expect(CLILocalCommandParser.parse("set k \(overValue)") == .invalid(.invalidCustomVarToken))
  }

  @Test
  func `custom-var set pair at the byte limit is accepted`() {
    let atLimit = "a" + String(repeating: "é", count: 68) // 1 + 136 = 137 bytes
    #expect("k:\(atLimit)".utf8.count == 139)
    #expect(CLILocalCommandParser.parse("set k \(atLimit)") == .command(.setCustomVar(key: "k", value: atLimit)))
  }

  @Test(arguments: [
    "set gps", // no value
    "set gps   ", // whitespace-only value
    "set _ 1", // empty post-strip key
    "get _", // empty post-strip key
  ])
  func `missing custom-var pieces render bad arguments`(line: String) {
    let usage: CLILocalUsage = line.hasPrefix("get") ? .get : .set
    #expect(CLILocalCommandParser.parse(line) == .invalid(.badArguments(usage)))
  }

  @Test(arguments: ["sensor list", "sensor get gps", "sensor set gps 1"])
  func `sensor is no longer a local command`(line: String) {
    #expect(CLILocalCommandParser.parse(line) == .notLocal)
  }
}
