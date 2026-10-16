package one.meshcore.shared.cli

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ports the Swift `CLICompletionEngineTests` suite in full: every table entry's completions,
 * case-insensitive matching with a case-sensitive final sort, argument arity, serial-only
 * exclusions, and the exact-list expectations.
 */
class CLICompletionEngineTest {
    private fun createEngine() = CLICompletionEngine()

    // MARK: - Command Completion Tests

    @Test
    fun emptyInputReturnsAllCommands() {
        val suggestions = createEngine().completions("", isLocal = true)

        assertContains(suggestions, "help")
        assertContains(suggestions, "clear")
        assertContains(suggestions, "login")
        assertContains(suggestions, "session")
    }

    @Test
    fun partialCommandReturnsMatchingCommands() {
        val suggestions = createEngine().completions("hel", isLocal = true)

        assertEquals(listOf("help"), suggestions)
    }

    @Test
    fun sessionSubcommandsCompleteAfterSessionSpace() {
        val suggestions = createEngine().completions("session ", isLocal = true)

        assertContains(suggestions, "list")
        assertContains(suggestions, "local")
    }

    @Test
    fun repeaterCommandsAvailableInRemoteSession() {
        val suggestions = createEngine().completions("v", isLocal = false)

        assertContains(suggestions, "ver")
    }

    @Test
    fun remoteCommandsIncludeRoomPostButLocalCommandsDoNot() {
        val engine = createEngine()
        assertContains(engine.completions("room", isLocal = false), "room.post")
        assertFalse("room.post" in engine.completions("room", isLocal = true))
    }

    @Test
    fun loginNotAvailableInRemoteSession() {
        val suggestions = createEngine().completions("log", isLocal = false)

        assertFalse(suggestions.contains("login"))
        assertContains(suggestions, "logout")
        assertContains(suggestions, "log")
    }

    // MARK: - Session Command Exclusion (Node CLI)

    @Test
    fun nodeCliCompletionsExcludeAppCliSessionCommands() {
        val suggestions = createEngine().completions("", isLocal = false, includeSessionCommands = false)

        assertFalse(suggestions.contains("session"))
        assertFalse(suggestions.contains("logout"))
        // Universal built-ins and node commands remain available.
        assertContains(suggestions, "help")
        assertContains(suggestions, "clear")
        assertContains(suggestions, "ver")
    }

    @Test
    fun nodeCliDoesNotSuggestLogoutForLogPrefix() {
        val suggestions = createEngine().completions("log", isLocal = false, includeSessionCommands = false)

        assertFalse(suggestions.contains("logout"))
        assertContains(suggestions, "log")
    }

    @Test
    fun appCliStillOffersSessionCommandsByDefault() {
        val suggestions = createEngine().completions("", isLocal = false)

        assertContains(suggestions, "session")
        assertContains(suggestions, "logout")
    }

    @Test
    fun regionSubcommandsCompleteAfterRegionSpace() {
        val suggestions = createEngine().completions("region ", isLocal = false)

        assertContains(suggestions, "load")
        assertContains(suggestions, "get")
        assertContains(suggestions, "put")
        assertContains(suggestions, "save")
        assertContains(suggestions, "default")
        assertContains(suggestions, "def")
    }

    @Test
    fun gpsSubcommandsCompleteAfterGpsSpace() {
        val suggestions = createEngine().completions("gps ", isLocal = false)

        assertContains(suggestions, "on")
        assertContains(suggestions, "off")
        assertContains(suggestions, "sync")
        assertContains(suggestions, "advert")
    }

    @Test
    fun getSetCompletesAllParameters() {
        val suggestions = createEngine().completions("get ", isLocal = false)

        assertContains(suggestions, "name")
        assertContains(suggestions, "radio")
        assertContains(suggestions, "flood.max")
        assertContains(suggestions, "bridge.enabled")
    }

    @Test
    fun clearSubcommandsComplete() {
        val suggestions = createEngine().completions("clear ", isLocal = false)

        assertContains(suggestions, "stats")
    }

    // MARK: - Log Subcommands Tests

    @Test
    fun logSubcommandsCompleteAfterLogSpace() {
        val suggestions = createEngine().completions("log ", isLocal = false)

        assertContains(suggestions, "start")
        assertContains(suggestions, "stop")
        assertContains(suggestions, "erase")
    }

    @Test
    fun logSubcommandFiltersByPrefix() {
        val suggestions = createEngine().completions("log st", isLocal = false)

        assertContains(suggestions, "start")
        assertContains(suggestions, "stop")
        assertFalse(suggestions.contains("erase"))
    }

    // MARK: - Powersaving Tests

    @Test
    fun powersavingValuesCompleteAfterPowersavingSpace() {
        val suggestions = createEngine().completions("powersaving ", isLocal = false)

        assertContains(suggestions, "on")
        assertContains(suggestions, "off")
    }

    @Test
    fun powersavingFiltersByPrefix() {
        val suggestions = createEngine().completions("powersaving o", isLocal = false)

        assertContains(suggestions, "on")
        assertContains(suggestions, "off")
    }

    // MARK: - GPS Advert Third Argument Tests

    @Test
    fun gpsAdvertValuesCompleteForThirdArgument() {
        val suggestions = createEngine().completions("gps advert ", isLocal = false)

        assertContains(suggestions, "none")
        assertContains(suggestions, "share")
        assertContains(suggestions, "prefs")
    }

    @Test
    fun gpsAdvertFiltersThirdArgumentByPrefix() {
        val suggestions = createEngine().completions("gps advert s", isLocal = false)

        assertContains(suggestions, "share")
        assertFalse(suggestions.contains("none"))
        assertFalse(suggestions.contains("prefs"))
    }

    // MARK: - Node Names Tests

    @Test
    fun updateNodeNamesStoresNodeNames() {
        val engine = createEngine()

        assertTrue(engine.nodeNames.isEmpty())

        engine.updateNodeNames(listOf("Alpha", "Bravo", "Charlie"))

        assertEquals(listOf("Alpha", "Bravo", "Charlie"), engine.nodeNames)
    }

    @Test
    fun updateNodeNamesReplacesPreviousNames() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("Alpha", "Bravo"))
        engine.updateNodeNames(listOf("Delta", "Echo"))

        assertEquals(listOf("Delta", "Echo"), engine.nodeNames)
    }

    // MARK: - Login with Node Names Tests

    @Test
    fun loginCompletesWithNodeNames() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("Alpha", "Bravo", "Charlie"))

        val suggestions = engine.completions("login ", isLocal = true)

        assertContains(suggestions, "Alpha")
        assertContains(suggestions, "Bravo")
        assertContains(suggestions, "Charlie")
    }

    @Test
    fun loginFiltersNodeNamesByPrefix() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("Alpha", "Bravo", "Charlie"))

        val suggestions = engine.completions("login a", isLocal = true)

        assertContains(suggestions, "Alpha")
        assertFalse(suggestions.contains("Bravo"))
        assertFalse(suggestions.contains("Charlie"))
    }

    @Test
    fun sessionIncludesNodeNamesInSuggestions() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("TestNode"))

        val suggestions = engine.completions("session ", isLocal = true)

        assertContains(suggestions, "list")
        assertContains(suggestions, "local")
        assertContains(suggestions, "TestNode")
    }

    @Test
    fun nodeNameCompletionIsCaseInsensitive() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("MyRepeater"))

        val suggestions = engine.completions("login my", isLocal = true)

        assertContains(suggestions, "MyRepeater")
    }

    @Test
    fun emptyNodeNamesReturnsEmptyForLogin() {
        val suggestions = createEngine().completions("login ", isLocal = true)

        assertTrue(suggestions.isEmpty())
    }

    // MARK: - Command Arity Tests (no suggestions after command complete)

    @Test
    fun loginReturnsEmptyAfterNodeNameComplete() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("MyRepeater"))

        val suggestions = engine.completions("login MyRepeater ", isLocal = true)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun sessionReturnsEmptyAfterSubcommandComplete() {
        val suggestions = createEngine().completions("session list ", isLocal = true)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun getReturnsEmptyAfterParameterComplete() {
        val suggestions = createEngine().completions("get name ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun gpsAdvertReturnsEmptyAfterValueComplete() {
        val suggestions = createEngine().completions("gps advert share ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun gpsOnReturnsEmptyAfterSubcommandComplete() {
        val suggestions = createEngine().completions("gps on ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun clearReturnsEmptyAfterStatsComplete() {
        val suggestions = createEngine().completions("clear stats ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun logReturnsEmptyAfterSubcommandComplete() {
        val suggestions = createEngine().completions("log start ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun powersavingReturnsEmptyAfterValueComplete() {
        val suggestions = createEngine().completions("powersaving on ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun regionReturnsEmptyAfterSubcommandComplete() {
        val suggestions = createEngine().completions("region load ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun clockSubcommandsCompleteAfterClockSpace() {
        val suggestions = createEngine().completions("clock ", isLocal = false)

        assertContains(suggestions, "sync")
    }

    @Test
    fun clockReturnsEmptyAfterSubcommandComplete() {
        val suggestions = createEngine().completions("clock sync ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    // MARK: - Partial Input Still Completes

    @Test
    fun loginPartialInputStillSuggests() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("MyRepeater"))

        val suggestions = engine.completions("login MyRep", isLocal = true)

        assertContains(suggestions, "MyRepeater")
    }

    @Test
    fun gpsAdvertPartialInputStillSuggests() {
        val suggestions = createEngine().completions("gps advert sh", isLocal = false)

        assertContains(suggestions, "share")
    }

    // MARK: - Case Sensitivity

    @Test
    fun uppercaseCommandStillRespectsArity() {
        val engine = createEngine()
        engine.updateNodeNames(listOf("MyRepeater"))

        val suggestions = engine.completions("LOGIN MyRepeater ", isLocal = true)

        assertTrue(suggestions.isEmpty())
    }

    // MARK: - v1.14.0 New Commands

    @Test
    fun advertZerohopAppearsInRemoteSessionSuggestions() {
        val suggestions = createEngine().completions("advert", isLocal = false)

        assertContains(suggestions, "advert")
        assertContains(suggestions, "advert.zerohop")
    }

    @Test
    fun discoverNeighborsAppearsInRemoteSessionSuggestions() {
        val suggestions = createEngine().completions("disc", isLocal = false)

        assertContains(suggestions, "discover.neighbors")
    }

    @Test
    fun newGetSetParamsAppearInCompletions() {
        val suggestions = createEngine().completions("get ", isLocal = false)

        assertContains(suggestions, "path.hash.mode")
        assertContains(suggestions, "loop.detect")
        assertContains(suggestions, "bootloader.ver")
    }

    @Test
    fun setLoopDetectSuggestsValues() {
        val suggestions = createEngine().completions("set loop.detect ", isLocal = false)

        assertEquals(listOf("minimal", "moderate", "off", "strict"), suggestions)
    }

    @Test
    fun setPathHashModeSuggestsValues() {
        val suggestions = createEngine().completions("set path.hash.mode ", isLocal = false)

        assertEquals(listOf("0", "1", "2"), suggestions)
    }

    @Test
    fun setLoopDetectReturnsEmptyAfterValueComplete() {
        val suggestions = createEngine().completions("set loop.detect off ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun getLoopDetectReturnsEmptyAfterParam() {
        val suggestions = createEngine().completions("get loop.detect ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun setLoopDetectFiltersValuesByPrefix() {
        val suggestions = createEngine().completions("set loop.detect m", isLocal = false)

        assertEquals(listOf("minimal", "moderate"), suggestions)
    }

    @Test
    fun uppercaseGpsAdvertStillSuggestsValues() {
        val suggestions = createEngine().completions("GPS ADVERT ", isLocal = false)

        assertContains(suggestions, "none")
        assertContains(suggestions, "prefs")
        assertContains(suggestions, "share")
    }

    // MARK: - Start OTA Tests

    @Test
    fun startAppearsInRemoteSessionSuggestions() {
        val suggestions = createEngine().completions("st", isLocal = false)

        assertContains(suggestions, "start")
    }

    @Test
    fun startOtaSubcommandCompletes() {
        val suggestions = createEngine().completions("start ", isLocal = false)

        assertEquals(listOf("ota"), suggestions)
    }

    @Test
    fun startReturnsEmptyAfterOtaComplete() {
        val suggestions = createEngine().completions("start ota ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    // MARK: - Region List Multi-Level Tests

    @Test
    fun regionListAppearsInSubcommands() {
        val suggestions = createEngine().completions("region ", isLocal = false)

        assertContains(suggestions, "list")
        assertContains(suggestions, "load")
        assertContains(suggestions, "get")
    }

    @Test
    fun regionListCompletesWithAllowedAndDenied() {
        val suggestions = createEngine().completions("region list ", isLocal = false)

        assertEquals(listOf("allowed", "denied"), suggestions)
    }

    @Test
    fun regionListFiltersValuesByPrefix() {
        val suggestions = createEngine().completions("region list a", isLocal = false)

        assertEquals(listOf("allowed"), suggestions)
    }

    @Test
    fun regionListReturnsEmptyAfterValueComplete() {
        val suggestions = createEngine().completions("region list allowed ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    @Test
    fun regionNonListSubcommandsReturnEmptyAtPositionTwo() {
        val suggestions = createEngine().completions("region get ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    // MARK: - New Get/Set Parameters Tests

    @Test
    fun newGetSetParamsIncludeOwnerInfoRadioRxgainBridgeChannelPwrmgt() {
        val suggestions = createEngine().completions("get ", isLocal = false)

        assertContains(suggestions, "owner.info")
        assertContains(suggestions, "radio.rxgain")
        assertContains(suggestions, "bridge.channel")
        assertContains(suggestions, "pwrmgt.support")
        assertContains(suggestions, "pwrmgt.source")
        assertContains(suggestions, "pwrmgt.bootreason")
        assertContains(suggestions, "pwrmgt.bootmv")
        assertContains(suggestions, "cad")
        assertContains(suggestions, "radio.fem.rxgain")
        assertContains(suggestions, "extra.sf")
    }

    @Test
    fun newOneSeventeenSwitchParamsSuggestOnOff() {
        val engine = createEngine()
        assertEquals(listOf("off", "on"), engine.completions("set cad ", isLocal = false))
        assertEquals(listOf("off", "on"), engine.completions("set radio.fem.rxgain ", isLocal = false))
    }

    @Test
    fun pwrmgtPrefixFiltersToFourParams() {
        val suggestions = createEngine().completions("get pwrmgt.", isLocal = false)

        assertEquals(4, suggestions.size)
        assertContains(suggestions, "pwrmgt.support")
        assertContains(suggestions, "pwrmgt.source")
        assertContains(suggestions, "pwrmgt.bootreason")
        assertContains(suggestions, "pwrmgt.bootmv")
    }

    // MARK: - New Set Value Completion Tests

    @Test
    fun setRepeatSuggestsOnOff() {
        val suggestions = createEngine().completions("set repeat ", isLocal = false)

        assertEquals(listOf("off", "on"), suggestions)
    }

    @Test
    fun setAllowReadOnlySuggestsOnOff() {
        val suggestions = createEngine().completions("set allow.read.only ", isLocal = false)

        assertEquals(listOf("off", "on"), suggestions)
    }

    @Test
    fun setBridgeEnabledSuggestsOnOff() {
        val suggestions = createEngine().completions("set bridge.enabled ", isLocal = false)

        assertEquals(listOf("off", "on"), suggestions)
    }

    @Test
    fun setRadioRxgainSuggestsOnOff() {
        val suggestions = createEngine().completions("set radio.rxgain ", isLocal = false)

        assertEquals(listOf("off", "on"), suggestions)
    }

    @Test
    fun setMultiAcksSuggestsZeroOne() {
        val suggestions = createEngine().completions("set multi.acks ", isLocal = false)

        assertEquals(listOf("0", "1"), suggestions)
    }

    @Test
    fun setBridgeSourceSuggestsTxRx() {
        val suggestions = createEngine().completions("set bridge.source ", isLocal = false)

        assertEquals(listOf("rx", "tx"), suggestions)
    }

    // MARK: - Serial-Only Exclusion Tests

    @Test
    fun getExcludesSerialOnlyParamsPrvKeyAndAcl() {
        val suggestions = createEngine().completions("get ", isLocal = false)

        assertFalse(suggestions.contains("prv.key"))
        assertFalse(suggestions.contains("acl"))
        // freq is only serial-only for set, not get
        assertContains(suggestions, "freq")
    }

    @Test
    fun getPrefixMatchingStillExcludesSerialOnlyParams() {
        val engine = createEngine()

        assertFalse(engine.completions("get prv", isLocal = false).contains("prv.key"))
        assertFalse(engine.completions("get a", isLocal = false).contains("acl"))
    }

    @Test
    fun setExcludesSerialOnlyParamFreq() {
        val suggestions = createEngine().completions("set ", isLocal = false)

        assertFalse(suggestions.contains("freq"))
        assertContains(suggestions, "prv.key")
    }

    @Test
    fun setPrefixMatchingStillExcludesSerialOnlyParamFreq() {
        val engine = createEngine()

        assertFalse(engine.completions("set f", isLocal = false).contains("freq"))
    }

    @Test
    fun setRepeatReturnsEmptyAfterValueComplete() {
        val suggestions = createEngine().completions("set repeat on ", isLocal = false)

        assertTrue(suggestions.isEmpty())
    }

    // MARK: - Local radio commands (RN-9)

    @Test
    fun localSessionOffersRadioCommands() {
        val suggestions = createEngine().completions("", isLocal = true)
        assertContains(suggestions, "clock")
        assertContains(suggestions, "ver")
        assertContains(suggestions, "board")
        assertContains(suggestions, "reboot")
        assertContains(suggestions, "floodadv")
    }

    @Test
    fun localGetCompletesTypedKeysAndCustomDumpVerb() {
        val suggestions = createEngine().completions("get ", isLocal = true)
        assertContains(suggestions, "name")
        assertContains(suggestions, "path.hash.mode")
        assertContains(suggestions, "custom")
    }

    @Test
    fun localSetExcludesReadOnlyKeys() {
        val suggestions = createEngine().completions("set ", isLocal = true)
        assertContains(suggestions, "tx")
        assertContains(suggestions, "radio")
        assertFalse(suggestions.contains("public.key"))
        assertFalse(suggestions.contains("bat"))
    }

    @Test
    fun localGetOffersLearnedCustomVarKeys() {
        val engine = createEngine()
        engine.updateCustomVarKeys(listOf("gpsMode", "wifi_ssid"))
        val suggestions = engine.completions("get gp", isLocal = true)
        assertContains(suggestions, "gpsMode")
    }

    @Test
    fun sensorIsRemoteOnly() {
        assertContains(createEngine().completions("sensor ", isLocal = false), "list")
        assertTrue(createEngine().completions("sensor ", isLocal = true).isEmpty())
    }
}
