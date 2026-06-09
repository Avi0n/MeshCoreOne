import Testing
@testable import MC1

@Suite("CLICompletionEngine Tests")
@MainActor
struct CLICompletionEngineTests {

    // MARK: - Helper

    private func createEngine() -> CLICompletionEngine {
        CLICompletionEngine()
    }

    // MARK: - Command Completion Tests

    @Test("Empty input returns all commands")
    func emptyInputReturnsAllCommands() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "", isLocal: true)

        #expect(suggestions.contains("help"))
        #expect(suggestions.contains("clear"))
        #expect(suggestions.contains("login"))
        #expect(suggestions.contains("session"))
    }

    @Test("Partial command returns matching commands")
    func partialCommandReturnsMatchingCommands() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "hel", isLocal: true)

        #expect(suggestions == ["help"])
    }

    @Test("Session subcommands complete after 'session '")
    func sessionSubcommandsComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "session ", isLocal: true)

        #expect(suggestions.contains("list"))
        #expect(suggestions.contains("local"))
    }

    @Test("Repeater commands available in remote session")
    func repeaterCommandsInRemoteSession() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "v", isLocal: false)

        #expect(suggestions.contains("ver"))
    }

    @Test("Login not available in remote session")
    func loginNotAvailableInRemoteSession() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "log", isLocal: false)

        #expect(!suggestions.contains("login"))
        #expect(suggestions.contains("logout"))
        #expect(suggestions.contains("log"))
    }

    // MARK: - Session Command Exclusion (Node CLI)

    @Test("Node CLI completions exclude app-CLI session commands")
    func nodeCompletionsExcludeSessionCommands() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "", isLocal: false, includeSessionCommands: false)

        #expect(!suggestions.contains("session"))
        #expect(!suggestions.contains("logout"))
        // Universal built-ins and node commands remain available.
        #expect(suggestions.contains("help"))
        #expect(suggestions.contains("clear"))
        #expect(suggestions.contains("ver"))
    }

    @Test("Node CLI does not suggest logout for a 'log' prefix")
    func nodeCompletionsExcludeLogoutPrefix() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "log", isLocal: false, includeSessionCommands: false)

        #expect(!suggestions.contains("logout"))
        #expect(suggestions.contains("log"))
    }

    @Test("App CLI still offers session commands by default")
    func appCompletionsIncludeSessionCommandsByDefault() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "", isLocal: false)

        #expect(suggestions.contains("session"))
        #expect(suggestions.contains("logout"))
    }

    @Test("Region subcommands complete after 'region '")
    func regionSubcommandsComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "region ", isLocal: false)

        #expect(suggestions.contains("load"))
        #expect(suggestions.contains("get"))
        #expect(suggestions.contains("put"))
        #expect(suggestions.contains("save"))
    }

    @Test("GPS subcommands complete after 'gps '")
    func gpsSubcommandsComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "gps ", isLocal: false)

        #expect(suggestions.contains("on"))
        #expect(suggestions.contains("off"))
        #expect(suggestions.contains("sync"))
        #expect(suggestions.contains("advert"))
    }

    @Test("Get/set completes all parameters")
    func getSetCompletesParameters() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "get ", isLocal: false)

        #expect(suggestions.contains("name"))
        #expect(suggestions.contains("radio"))
        #expect(suggestions.contains("flood.max"))
        #expect(suggestions.contains("bridge.enabled"))
    }

    @Test("Clear subcommands complete")
    func clearSubcommandsComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "clear ", isLocal: false)

        #expect(suggestions.contains("stats"))
    }

    // MARK: - Log Subcommands Tests

    @Test("Log subcommands complete after 'log '")
    func logSubcommandsComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "log ", isLocal: false)

        #expect(suggestions.contains("start"))
        #expect(suggestions.contains("stop"))
        #expect(suggestions.contains("erase"))
    }

    @Test("Log subcommand filters by prefix")
    func logSubcommandFiltersPrefix() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "log st", isLocal: false)

        #expect(suggestions.contains("start"))
        #expect(suggestions.contains("stop"))
        #expect(!suggestions.contains("erase"))
    }

    // MARK: - Powersaving Tests

    @Test("Powersaving values complete after 'powersaving '")
    func powersavingValuesComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "powersaving ", isLocal: false)

        #expect(suggestions.contains("on"))
        #expect(suggestions.contains("off"))
    }

    @Test("Powersaving filters by prefix")
    func powersavingFiltersPrefix() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "powersaving o", isLocal: false)

        #expect(suggestions.contains("on"))
        #expect(suggestions.contains("off"))
    }

    // MARK: - GPS Advert Third Argument Tests

    @Test("GPS advert values complete for third argument")
    func gpsAdvertValuesComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "gps advert ", isLocal: false)

        #expect(suggestions.contains("none"))
        #expect(suggestions.contains("share"))
        #expect(suggestions.contains("prefs"))
    }

    @Test("GPS advert filters third argument by prefix")
    func gpsAdvertFiltersPrefix() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "gps advert s", isLocal: false)

        #expect(suggestions.contains("share"))
        #expect(!suggestions.contains("none"))
        #expect(!suggestions.contains("prefs"))
    }

    // MARK: - Node Names Tests

    @Test("updateNodeNames stores node names")
    func updateNodeNamesStoresNames() {
        let engine = createEngine()

        #expect(engine.nodeNames.isEmpty)

        engine.updateNodeNames(["Alpha", "Bravo", "Charlie"])

        #expect(engine.nodeNames == ["Alpha", "Bravo", "Charlie"])
    }

    @Test("updateNodeNames replaces previous names")
    func updateNodeNamesReplacesPrevious() {
        let engine = createEngine()
        engine.updateNodeNames(["Alpha", "Bravo"])
        engine.updateNodeNames(["Delta", "Echo"])

        #expect(engine.nodeNames == ["Delta", "Echo"])
    }

    // MARK: - Login with Node Names Tests

    @Test("Login completes with node names")
    func loginCompletesWithNodeNames() {
        let engine = createEngine()
        engine.updateNodeNames(["Alpha", "Bravo", "Charlie"])

        let suggestions = engine.completions(for: "login ", isLocal: true)

        #expect(suggestions.contains("Alpha"))
        #expect(suggestions.contains("Bravo"))
        #expect(suggestions.contains("Charlie"))
    }

    @Test("Login filters node names by prefix")
    func loginFiltersNodeNamesByPrefix() {
        let engine = createEngine()
        engine.updateNodeNames(["Alpha", "Bravo", "Charlie"])

        let suggestions = engine.completions(for: "login a", isLocal: true)

        #expect(suggestions.contains("Alpha"))
        #expect(!suggestions.contains("Bravo"))
        #expect(!suggestions.contains("Charlie"))
    }

    @Test("Session includes node names in suggestions")
    func sessionIncludesNodeNames() {
        let engine = createEngine()
        engine.updateNodeNames(["TestNode"])

        let suggestions = engine.completions(for: "session ", isLocal: true)

        #expect(suggestions.contains("list"))
        #expect(suggestions.contains("local"))
        #expect(suggestions.contains("TestNode"))
    }

    @Test("Node name completion is case-insensitive")
    func nodeNameCompletionCaseInsensitive() {
        let engine = createEngine()
        engine.updateNodeNames(["MyRepeater"])

        let suggestions = engine.completions(for: "login my", isLocal: true)

        #expect(suggestions.contains("MyRepeater"))
    }

    @Test("Empty node names returns empty for login")
    func emptyNodeNamesReturnsEmptyForLogin() {
        let engine = createEngine()
        // No updateNodeNames called

        let suggestions = engine.completions(for: "login ", isLocal: true)

        #expect(suggestions.isEmpty)
    }

    // MARK: - Command Arity Tests (no suggestions after command complete)

    @Test("Login returns empty after node name complete")
    func loginReturnsEmptyAfterNodeNameComplete() {
        let engine = createEngine()
        engine.updateNodeNames(["MyRepeater"])

        let suggestions = engine.completions(for: "login MyRepeater ", isLocal: true)

        #expect(suggestions.isEmpty)
    }

    @Test("Session returns empty after subcommand complete")
    func sessionReturnsEmptyAfterSubcommandComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "session list ", isLocal: true)

        #expect(suggestions.isEmpty)
    }

    @Test("Get returns empty after parameter complete")
    func getReturnsEmptyAfterParameterComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "get name ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("GPS advert returns empty after value complete")
    func gpsAdvertReturnsEmptyAfterValueComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "gps advert share ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("GPS on returns empty after subcommand complete")
    func gpsOnReturnsEmptyAfterSubcommandComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "gps on ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("Clear returns empty after stats complete")
    func clearReturnsEmptyAfterStatsComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "clear stats ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("Log returns empty after subcommand complete")
    func logReturnsEmptyAfterSubcommandComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "log start ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("Powersaving returns empty after value complete")
    func powersavingReturnsEmptyAfterValueComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "powersaving on ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("Region returns empty after subcommand complete")
    func regionReturnsEmptyAfterSubcommandComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "region load ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("Clock subcommands complete after 'clock '")
    func clockSubcommandsComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "clock ", isLocal: false)

        #expect(suggestions.contains("sync"))
    }

    @Test("Clock returns empty after subcommand complete")
    func clockReturnsEmptyAfterSubcommandComplete() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "clock sync ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    // MARK: - Partial Input Still Completes

    @Test("Login partial input still suggests")
    func loginPartialInputStillSuggests() {
        let engine = createEngine()
        engine.updateNodeNames(["MyRepeater"])

        let suggestions = engine.completions(for: "login MyRep", isLocal: true)

        #expect(suggestions.contains("MyRepeater"))
    }

    @Test("GPS advert partial input still suggests")
    func gpsAdvertPartialInputStillSuggests() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "gps advert sh", isLocal: false)

        #expect(suggestions.contains("share"))
    }

    // MARK: - Case Sensitivity

    @Test("Uppercase command still respects arity")
    func uppercaseCommandRespectsArity() {
        let engine = createEngine()
        engine.updateNodeNames(["MyRepeater"])

        let suggestions = engine.completions(for: "LOGIN MyRepeater ", isLocal: true)

        #expect(suggestions.isEmpty)
    }

    // MARK: - v1.14.0 New Commands

    @Test("advert.zerohop appears in remote session suggestions")
    func advertZerohopAppearsInRemote() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "advert", isLocal: false)

        #expect(suggestions.contains("advert"))
        #expect(suggestions.contains("advert.zerohop"))
    }

    @Test("discover.neighbors appears in remote session suggestions")
    func discoverNeighborsAppearsInRemote() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "disc", isLocal: false)

        #expect(suggestions.contains("discover.neighbors"))
    }

    @Test("New get/set params appear in completions")
    func newGetSetParamsAppear() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "get ", isLocal: false)

        #expect(suggestions.contains("path.hash.mode"))
        #expect(suggestions.contains("loop.detect"))
        #expect(suggestions.contains("bootloader.ver"))
    }

    @Test("set loop.detect suggests values")
    func setLoopDetectSuggestsValues() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set loop.detect ", isLocal: false)

        #expect(suggestions == ["minimal", "moderate", "off", "strict"])
    }

    @Test("set path.hash.mode suggests values")
    func setPathHashModeSuggestsValues() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set path.hash.mode ", isLocal: false)

        #expect(suggestions == ["0", "1", "2"])
    }

    @Test("set loop.detect returns empty after value complete")
    func setLoopDetectReturnsEmptyAfterValue() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set loop.detect off ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("get loop.detect returns empty after param (no value completion for get)")
    func getLoopDetectNoValueCompletion() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "get loop.detect ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("set loop.detect filters values by prefix")
    func setLoopDetectFiltersPrefix() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set loop.detect m", isLocal: false)

        #expect(suggestions == ["minimal", "moderate"])
    }

    @Test("Uppercase GPS advert still suggests values")
    func uppercaseGpsAdvertSuggestsValues() {
        let engine = createEngine()

        let suggestions = engine.completions(for: "GPS ADVERT ", isLocal: false)

        #expect(suggestions.contains("none"))
        #expect(suggestions.contains("prefs"))
        #expect(suggestions.contains("share"))
    }

    // MARK: - Start OTA Tests

    @Test("start appears in remote session suggestions")
    func startAppearsInRemote() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "st", isLocal: false)

        #expect(suggestions.contains("start"))
    }

    @Test("start ota subcommand completes")
    func startOtaSubcommandCompletes() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "start ", isLocal: false)

        #expect(suggestions == ["ota"])
    }

    @Test("start returns empty after ota complete")
    func startReturnsEmptyAfterOtaComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "start ota ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    // MARK: - Region List Multi-Level Tests

    @Test("Region list appears in subcommands")
    func regionListAppearsInSubcommands() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "region ", isLocal: false)

        #expect(suggestions.contains("list"))
        #expect(suggestions.contains("load"))
        #expect(suggestions.contains("get"))
    }

    @Test("Region list completes with allowed and denied")
    func regionListCompletesWithValues() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "region list ", isLocal: false)

        #expect(suggestions == ["allowed", "denied"])
    }

    @Test("Region list filters values by prefix")
    func regionListFiltersPrefix() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "region list a", isLocal: false)

        #expect(suggestions == ["allowed"])
    }

    @Test("Region list returns empty after value complete")
    func regionListReturnsEmptyAfterValueComplete() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "region list allowed ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    @Test("Region non-list subcommands return empty at position 2")
    func regionNonListReturnsEmptyAtPosition2() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "region get ", isLocal: false)

        #expect(suggestions.isEmpty)
    }

    // MARK: - New Get/Set Parameters Tests

    @Test("New get/set params include owner.info, radio.rxgain, bridge.channel, pwrmgt")
    func newGetSetParamsIncludeAdded() {
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

    @Test("pwrmgt prefix filters to four params")
    func pwrmgtPrefixFilters() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "get pwrmgt.", isLocal: false)

        #expect(suggestions.count == 4)
        #expect(suggestions.contains("pwrmgt.support"))
        #expect(suggestions.contains("pwrmgt.source"))
        #expect(suggestions.contains("pwrmgt.bootreason"))
        #expect(suggestions.contains("pwrmgt.bootmv"))
    }

    // MARK: - New Set Value Completion Tests

    @Test("set repeat suggests on/off")
    func setRepeatSuggestsOnOff() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set repeat ", isLocal: false)

        #expect(suggestions == ["off", "on"])
    }

    @Test("set allow.read.only suggests on/off")
    func setAllowReadOnlySuggestsOnOff() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set allow.read.only ", isLocal: false)

        #expect(suggestions == ["off", "on"])
    }

    @Test("set bridge.enabled suggests on/off")
    func setBridgeEnabledSuggestsOnOff() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set bridge.enabled ", isLocal: false)

        #expect(suggestions == ["off", "on"])
    }

    @Test("set radio.rxgain suggests on/off")
    func setRadioRxgainSuggestsOnOff() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set radio.rxgain ", isLocal: false)

        #expect(suggestions == ["off", "on"])
    }

    @Test("set multi.acks suggests 0/1")
    func setMultiAcksSuggestsValues() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set multi.acks ", isLocal: false)

        #expect(suggestions == ["0", "1"])
    }

    @Test("set bridge.source suggests tx/rx")
    func setBridgeSourceSuggestsValues() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set bridge.source ", isLocal: false)

        #expect(suggestions == ["rx", "tx"])
    }

    // MARK: - Serial-Only Exclusion Tests

    @Test("get excludes serial-only params prv.key and acl")
    func getExcludesSerialOnlyParams() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "get ", isLocal: false)

        #expect(!suggestions.contains("prv.key"))
        #expect(!suggestions.contains("acl"))
        // freq is only serial-only for set, not get
        #expect(suggestions.contains("freq"))
    }

    @Test("get prefix matching still excludes serial-only params")
    func getPrefixExcludesSerialOnly() {
        let engine = createEngine()

        #expect(!engine.completions(for: "get prv", isLocal: false).contains("prv.key"))
        #expect(!engine.completions(for: "get a", isLocal: false).contains("acl"))
    }

    @Test("set excludes serial-only param freq")
    func setExcludesSerialOnlyParams() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set ", isLocal: false)

        #expect(!suggestions.contains("freq"))
        #expect(suggestions.contains("prv.key"))
    }

    @Test("set prefix matching still excludes serial-only param freq")
    func setPrefixExcludesSerialOnly() {
        let engine = createEngine()

        #expect(!engine.completions(for: "set f", isLocal: false).contains("freq"))
    }

    @Test("set repeat returns empty after value complete")
    func setRepeatReturnsEmptyAfterValue() {
        let engine = createEngine()
        let suggestions = engine.completions(for: "set repeat on ", isLocal: false)

        #expect(suggestions.isEmpty)
    }
}
