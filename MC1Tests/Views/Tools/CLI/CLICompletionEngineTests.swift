@testable import MC1
import Testing

@Suite("CLICompletionEngine Tests")
@MainActor
struct CLICompletionEngineTests {
  // MARK: - Helper

  private func createEngine() -> CLICompletionEngine {
    CLICompletionEngine()
  }

  // MARK: - Command Completion Tests

  @Test
  func `Empty input returns all commands`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "", isLocal: true)

    #expect(suggestions.contains("help"))
    #expect(suggestions.contains("clear"))
    #expect(suggestions.contains("login"))
    #expect(suggestions.contains("session"))
  }

  @Test
  func `Partial command returns matching commands`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "hel", isLocal: true)

    #expect(suggestions == ["help"])
  }

  @Test
  func `Session subcommands complete after 'session '`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "session ", isLocal: true)

    #expect(suggestions.contains("list"))
    #expect(suggestions.contains("local"))
  }

  @Test
  func `Repeater commands available in remote session`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "v", isLocal: false)

    #expect(suggestions.contains("ver"))
  }

  @Test
  func `Login not available in remote session`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "log", isLocal: false)

    #expect(!suggestions.contains("login"))
    #expect(suggestions.contains("logout"))
    #expect(suggestions.contains("log"))
  }

  // MARK: - Session Command Exclusion (Node CLI)

  @Test
  func `Node CLI completions exclude app-CLI session commands`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "", isLocal: false, includeSessionCommands: false)

    #expect(!suggestions.contains("session"))
    #expect(!suggestions.contains("logout"))
    // Universal built-ins and node commands remain available.
    #expect(suggestions.contains("help"))
    #expect(suggestions.contains("clear"))
    #expect(suggestions.contains("ver"))
  }

  @Test
  func `Node CLI does not suggest logout for a 'log' prefix`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "log", isLocal: false, includeSessionCommands: false)

    #expect(!suggestions.contains("logout"))
    #expect(suggestions.contains("log"))
  }

  @Test
  func `App CLI still offers session commands by default`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "", isLocal: false)

    #expect(suggestions.contains("session"))
    #expect(suggestions.contains("logout"))
  }

  @Test
  func `Region subcommands complete after 'region '`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "region ", isLocal: false)

    #expect(suggestions.contains("load"))
    #expect(suggestions.contains("get"))
    #expect(suggestions.contains("put"))
    #expect(suggestions.contains("save"))
  }

  @Test
  func `GPS subcommands complete after 'gps '`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "gps ", isLocal: false)

    #expect(suggestions.contains("on"))
    #expect(suggestions.contains("off"))
    #expect(suggestions.contains("sync"))
    #expect(suggestions.contains("advert"))
  }

  @Test
  func `Get/set completes all parameters`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "get ", isLocal: false)

    #expect(suggestions.contains("name"))
    #expect(suggestions.contains("radio"))
    #expect(suggestions.contains("flood.max"))
    #expect(suggestions.contains("bridge.enabled"))
  }

  @Test
  func `Clear subcommands complete`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "clear ", isLocal: false)

    #expect(suggestions.contains("stats"))
  }

  // MARK: - Log Subcommands Tests

  @Test
  func `Log subcommands complete after 'log '`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "log ", isLocal: false)

    #expect(suggestions.contains("start"))
    #expect(suggestions.contains("stop"))
    #expect(suggestions.contains("erase"))
  }

  @Test
  func `Log subcommand filters by prefix`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "log st", isLocal: false)

    #expect(suggestions.contains("start"))
    #expect(suggestions.contains("stop"))
    #expect(!suggestions.contains("erase"))
  }

  // MARK: - Powersaving Tests

  @Test
  func `Powersaving values complete after 'powersaving '`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "powersaving ", isLocal: false)

    #expect(suggestions.contains("on"))
    #expect(suggestions.contains("off"))
  }

  @Test
  func `Powersaving filters by prefix`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "powersaving o", isLocal: false)

    #expect(suggestions.contains("on"))
    #expect(suggestions.contains("off"))
  }

  // MARK: - GPS Advert Third Argument Tests

  @Test
  func `GPS advert values complete for third argument`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "gps advert ", isLocal: false)

    #expect(suggestions.contains("none"))
    #expect(suggestions.contains("share"))
    #expect(suggestions.contains("prefs"))
  }

  @Test
  func `GPS advert filters third argument by prefix`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "gps advert s", isLocal: false)

    #expect(suggestions.contains("share"))
    #expect(!suggestions.contains("none"))
    #expect(!suggestions.contains("prefs"))
  }

  // MARK: - Node Names Tests

  @Test
  func `updateNodeNames stores node names`() {
    let engine = createEngine()

    #expect(engine.nodeNames.isEmpty)

    engine.updateNodeNames(["Alpha", "Bravo", "Charlie"])

    #expect(engine.nodeNames == ["Alpha", "Bravo", "Charlie"])
  }

  @Test
  func `updateNodeNames replaces previous names`() {
    let engine = createEngine()
    engine.updateNodeNames(["Alpha", "Bravo"])
    engine.updateNodeNames(["Delta", "Echo"])

    #expect(engine.nodeNames == ["Delta", "Echo"])
  }

  // MARK: - Login with Node Names Tests

  @Test
  func `Login completes with node names`() {
    let engine = createEngine()
    engine.updateNodeNames(["Alpha", "Bravo", "Charlie"])

    let suggestions = engine.completions(for: "login ", isLocal: true)

    #expect(suggestions.contains("Alpha"))
    #expect(suggestions.contains("Bravo"))
    #expect(suggestions.contains("Charlie"))
  }

  @Test
  func `Login filters node names by prefix`() {
    let engine = createEngine()
    engine.updateNodeNames(["Alpha", "Bravo", "Charlie"])

    let suggestions = engine.completions(for: "login a", isLocal: true)

    #expect(suggestions.contains("Alpha"))
    #expect(!suggestions.contains("Bravo"))
    #expect(!suggestions.contains("Charlie"))
  }

  @Test
  func `Session includes node names in suggestions`() {
    let engine = createEngine()
    engine.updateNodeNames(["TestNode"])

    let suggestions = engine.completions(for: "session ", isLocal: true)

    #expect(suggestions.contains("list"))
    #expect(suggestions.contains("local"))
    #expect(suggestions.contains("TestNode"))
  }

  @Test
  func `Node name completion is case-insensitive`() {
    let engine = createEngine()
    engine.updateNodeNames(["MyRepeater"])

    let suggestions = engine.completions(for: "login my", isLocal: true)

    #expect(suggestions.contains("MyRepeater"))
  }

  @Test
  func `Empty node names returns empty for login`() {
    let engine = createEngine()
    // No updateNodeNames called

    let suggestions = engine.completions(for: "login ", isLocal: true)

    #expect(suggestions.isEmpty)
  }

  // MARK: - Command Arity Tests (no suggestions after command complete)

  @Test
  func `Login returns empty after node name complete`() {
    let engine = createEngine()
    engine.updateNodeNames(["MyRepeater"])

    let suggestions = engine.completions(for: "login MyRepeater ", isLocal: true)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Session returns empty after subcommand complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "session list ", isLocal: true)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Get returns empty after parameter complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "get name ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `GPS advert returns empty after value complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "gps advert share ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `GPS on returns empty after subcommand complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "gps on ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Clear returns empty after stats complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "clear stats ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Log returns empty after subcommand complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "log start ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Powersaving returns empty after value complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "powersaving on ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Region returns empty after subcommand complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "region load ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Clock subcommands complete after 'clock '`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "clock ", isLocal: false)

    #expect(suggestions.contains("sync"))
  }

  @Test
  func `Clock returns empty after subcommand complete`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "clock sync ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  // MARK: - Partial Input Still Completes

  @Test
  func `Login partial input still suggests`() {
    let engine = createEngine()
    engine.updateNodeNames(["MyRepeater"])

    let suggestions = engine.completions(for: "login MyRep", isLocal: true)

    #expect(suggestions.contains("MyRepeater"))
  }

  @Test
  func `GPS advert partial input still suggests`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "gps advert sh", isLocal: false)

    #expect(suggestions.contains("share"))
  }

  // MARK: - Case Sensitivity

  @Test
  func `Uppercase command still respects arity`() {
    let engine = createEngine()
    engine.updateNodeNames(["MyRepeater"])

    let suggestions = engine.completions(for: "LOGIN MyRepeater ", isLocal: true)

    #expect(suggestions.isEmpty)
  }

  // MARK: - v1.14.0 New Commands

  @Test
  func `advert.zerohop appears in remote session suggestions`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "advert", isLocal: false)

    #expect(suggestions.contains("advert"))
    #expect(suggestions.contains("advert.zerohop"))
  }

  @Test
  func `discover.neighbors appears in remote session suggestions`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "disc", isLocal: false)

    #expect(suggestions.contains("discover.neighbors"))
  }

  @Test
  func `New get/set params appear in completions`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "get ", isLocal: false)

    #expect(suggestions.contains("path.hash.mode"))
    #expect(suggestions.contains("loop.detect"))
    #expect(suggestions.contains("bootloader.ver"))
  }

  @Test
  func `set loop.detect suggests values`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set loop.detect ", isLocal: false)

    #expect(suggestions == ["minimal", "moderate", "off", "strict"])
  }

  @Test
  func `set path.hash.mode suggests values`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set path.hash.mode ", isLocal: false)

    #expect(suggestions == ["0", "1", "2"])
  }

  @Test
  func `set loop.detect returns empty after value complete`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set loop.detect off ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `get loop.detect returns empty after param (no value completion for get)`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "get loop.detect ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `set loop.detect filters values by prefix`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set loop.detect m", isLocal: false)

    #expect(suggestions == ["minimal", "moderate"])
  }

  @Test
  func `Uppercase GPS advert still suggests values`() {
    let engine = createEngine()

    let suggestions = engine.completions(for: "GPS ADVERT ", isLocal: false)

    #expect(suggestions.contains("none"))
    #expect(suggestions.contains("prefs"))
    #expect(suggestions.contains("share"))
  }

  // MARK: - Start OTA Tests

  @Test
  func `start appears in remote session suggestions`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "st", isLocal: false)

    #expect(suggestions.contains("start"))
  }

  @Test
  func `start ota subcommand completes`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "start ", isLocal: false)

    #expect(suggestions == ["ota"])
  }

  @Test
  func `start returns empty after ota complete`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "start ota ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  // MARK: - Region List Multi-Level Tests

  @Test
  func `Region list appears in subcommands`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "region ", isLocal: false)

    #expect(suggestions.contains("list"))
    #expect(suggestions.contains("load"))
    #expect(suggestions.contains("get"))
  }

  @Test
  func `Region list completes with allowed and denied`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "region list ", isLocal: false)

    #expect(suggestions == ["allowed", "denied"])
  }

  @Test
  func `Region list filters values by prefix`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "region list a", isLocal: false)

    #expect(suggestions == ["allowed"])
  }

  @Test
  func `Region list returns empty after value complete`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "region list allowed ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  @Test
  func `Region non-list subcommands return empty at position 2`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "region get ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  // MARK: - New Get/Set Parameters Tests

  @Test
  func `New get/set params include owner.info, radio.rxgain, bridge.channel, pwrmgt`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "get ", isLocal: false)

    #expect(suggestions.contains("owner.info"))
    #expect(suggestions.contains("radio.rxgain"))
    #expect(suggestions.contains("bridge.channel"))
    #expect(suggestions.contains("pwrmgt.support"))
    #expect(suggestions.contains("pwrmgt.source"))
    #expect(suggestions.contains("pwrmgt.bootreason"))
    #expect(suggestions.contains("pwrmgt.bootmv"))
  }

  @Test
  func `pwrmgt prefix filters to four params`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "get pwrmgt.", isLocal: false)

    #expect(suggestions.count == 4)
    #expect(suggestions.contains("pwrmgt.support"))
    #expect(suggestions.contains("pwrmgt.source"))
    #expect(suggestions.contains("pwrmgt.bootreason"))
    #expect(suggestions.contains("pwrmgt.bootmv"))
  }

  // MARK: - New Set Value Completion Tests

  @Test
  func `set repeat suggests on/off`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set repeat ", isLocal: false)

    #expect(suggestions == ["off", "on"])
  }

  @Test
  func `set allow.read.only suggests on/off`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set allow.read.only ", isLocal: false)

    #expect(suggestions == ["off", "on"])
  }

  @Test
  func `set bridge.enabled suggests on/off`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set bridge.enabled ", isLocal: false)

    #expect(suggestions == ["off", "on"])
  }

  @Test
  func `set radio.rxgain suggests on/off`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set radio.rxgain ", isLocal: false)

    #expect(suggestions == ["off", "on"])
  }

  @Test
  func `set multi.acks suggests 0/1`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set multi.acks ", isLocal: false)

    #expect(suggestions == ["0", "1"])
  }

  @Test
  func `set bridge.source suggests tx/rx`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set bridge.source ", isLocal: false)

    #expect(suggestions == ["rx", "tx"])
  }

  // MARK: - Serial-Only Exclusion Tests

  @Test
  func `get excludes serial-only params prv.key and acl`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "get ", isLocal: false)

    #expect(!suggestions.contains("prv.key"))
    #expect(!suggestions.contains("acl"))
    // freq is only serial-only for set, not get
    #expect(suggestions.contains("freq"))
  }

  @Test
  func `get prefix matching still excludes serial-only params`() {
    let engine = createEngine()

    #expect(!engine.completions(for: "get prv", isLocal: false).contains("prv.key"))
    #expect(!engine.completions(for: "get a", isLocal: false).contains("acl"))
  }

  @Test
  func `set excludes serial-only param freq`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set ", isLocal: false)

    #expect(!suggestions.contains("freq"))
    #expect(suggestions.contains("prv.key"))
  }

  @Test
  func `set prefix matching still excludes serial-only param freq`() {
    let engine = createEngine()

    #expect(!engine.completions(for: "set f", isLocal: false).contains("freq"))
  }

  @Test
  func `set repeat returns empty after value complete`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set repeat on ", isLocal: false)

    #expect(suggestions.isEmpty)
  }

  // MARK: - Local Radio Command Vocabulary

  @Test
  func `Local session suggests local radio commands`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "", isLocal: true)

    #expect(suggestions.contains("floodadv"))
    #expect(suggestions.contains("reboot"))
    #expect(suggestions.contains("get"))
    #expect(suggestions.contains("board"))
  }

  @Test
  func `Local session does not suggest repeater-only commands`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "", isLocal: true)

    #expect(!suggestions.contains("neighbors"))
    #expect(!suggestions.contains("password"))
    #expect(!suggestions.contains("setperm"))
  }

  @Test
  func `Remote session does not suggest floodadv`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "", isLocal: false)

    #expect(!suggestions.contains("floodadv"))
  }

  @Test
  func `get keys differ per session`() {
    let engine = createEngine()
    let local = engine.completions(for: "get ", isLocal: true)
    let remote = engine.completions(for: "get ", isLocal: false)

    #expect(local.contains("bat"))
    #expect(!local.contains("role"))
    #expect(remote.contains("role"))
    #expect(!remote.contains("bat"))
  }

  @Test
  func `local set keys exclude read-only keys`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "set ", isLocal: true)

    #expect(suggestions.contains("name"))
    #expect(suggestions.contains("multi.acks"))
    #expect(!suggestions.contains("public.key"))
    #expect(!suggestions.contains("bat"))
  }

  @Test
  func `clock completes sync on local session`() {
    let engine = createEngine()
    let suggestions = engine.completions(for: "clock ", isLocal: true)

    #expect(suggestions.contains("sync"))
  }

  // MARK: - Custom vars (bare get/set; remote sensor)

  @Test
  func `sensor is remote-only`() {
    let engine = createEngine()

    #expect(!engine.completions(for: "sen", isLocal: true).contains("sensor"))
    #expect(engine.completions(for: "sen", isLocal: false).contains("sensor"))
  }

  @Test
  func `sensor subcommands complete on a remote session only`() {
    let engine = createEngine()

    #expect(engine.completions(for: "sensor ", isLocal: false) == ["get", "list", "set"])
    #expect(engine.completions(for: "sensor ", isLocal: true).isEmpty)
  }

  @Test
  func `bare get on local offers typed keys, the dump verb, and learned custom keys`() {
    let engine = createEngine()
    engine.updateCustomVarKeys(["gps", "wifi_ssid"])

    let suggestions = engine.completions(for: "get ", isLocal: true)

    #expect(suggestions.contains("custom"))
    #expect(suggestions.contains("name")) // typed
    #expect(suggestions.contains("gps")) // learned custom
    #expect(suggestions.contains("wifi_ssid"))
    #expect(suggestions == suggestions.sorted())
  }

  @Test
  func `bare set on local offers typed and learned custom keys but not the dump verb`() {
    let engine = createEngine()
    engine.updateCustomVarKeys(["gps"])

    let suggestions = engine.completions(for: "set ", isLocal: true)

    #expect(suggestions.contains("name")) // typed
    #expect(suggestions.contains("gps")) // learned custom
    #expect(!suggestions.contains("custom")) // dump verb is get-only
  }

  @Test
  func `bare get and set custom keys match case-insensitively but suggest verbatim`() {
    let engine = createEngine()
    engine.updateCustomVarKeys(["WiFi_SSID"])

    #expect(engine.completions(for: "get wifi", isLocal: true) == ["WiFi_SSID"])
    #expect(engine.completions(for: "set wifi", isLocal: true) == ["WiFi_SSID"])
  }

  @Test
  func `bare get dedupes a custom key that collides with a typed key`() {
    let engine = createEngine()
    engine.updateCustomVarKeys(["name"])

    #expect(engine.completions(for: "get name", isLocal: true) == ["name"])
  }

  @Test
  func `bare get and set value position offers no completion`() {
    let engine = createEngine()
    engine.updateCustomVarKeys(["gps"])

    #expect(engine.completions(for: "get gps ", isLocal: true).isEmpty)
    #expect(engine.completions(for: "set gps ", isLocal: true).isEmpty)
  }

  @Test
  func `updateCustomVarKeys replaces previous keys`() {
    let engine = createEngine()
    engine.updateCustomVarKeys(["a", "b"])
    engine.updateCustomVarKeys(["c"])

    #expect(engine.customVarKeys == ["c"])
  }
}
