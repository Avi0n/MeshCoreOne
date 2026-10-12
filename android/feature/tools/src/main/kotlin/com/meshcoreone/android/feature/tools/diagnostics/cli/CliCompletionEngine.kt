// PortedFrom: MC1/Views/Tools/CLI/CLICompletionEngine.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftStrings

/**
 * Tab/ghost completion vocabulary for the app CLI (local + remote sessions) and the node CLI.
 * Confined to the owning state holder's thread, like the Swift `@MainActor` engine; the learned
 * name/key lists are replaced wholesale, never mutated.
 */
class CliCompletionEngine {
    private companion object {
        val BUILT_IN_COMMANDS = listOf("help", "clear")

        /** App-CLI session management; the node CLI passes these straight to firmware. */
        val SESSION_COMMANDS = listOf("session", "logout")
        val LOCAL_ONLY_COMMANDS = listOf("login", "nodes", "channels")
        val LOCAL_RADIO_COMMANDS = listOf("advert", "advert.zerohop", "board", "clock", "floodadv", "get", "reboot", "set", "ver")
        val LOCAL_GET_KEYS = CliLocalKey.entries.map { it.rawValue }
        const val CUSTOM_VAR_DUMP_KEY = "custom"

        /** Every typed key the parser's `set` accepts: all keys minus read-only `public.key`/`bat`. */
        val LOCAL_SET_KEYS = CliLocalKey.entries
            .filter { it != CliLocalKey.PUBLIC_KEY && it != CliLocalKey.BAT }
            .map { it.rawValue }

        /** Per MeshCore CLI Reference: commands available via remote session. */
        val REPEATER_COMMANDS = listOf(
            "ver", "board", "clock", "clkreboot",
            "neighbors", "get", "set", "sensor", "password",
            "log", "reboot", "room.post", "advert", "advert.zerohop", "setperm", "tempradio", "neighbor.remove",
            "region", "gps", "powersaving", "clear", "discover.neighbors",
            "start",
        )
        val SESSION_SUBCOMMANDS = listOf("list", "local")
        val SENSOR_SUBCOMMANDS = listOf("get", "list", "set")
        val LOG_SUBCOMMANDS = listOf("start", "stop", "erase")
        val CLEAR_SUBCOMMANDS = listOf("stats")
        val CLOCK_SUBCOMMANDS = listOf("sync")
        val REGION_SUBCOMMANDS = listOf("load", "get", "put", "remove", "allowf", "denyf", "home", "default", "save", "list", "def")
        val GPS_SUBCOMMANDS = listOf("on", "off", "sync", "setloc", "advert")
        val GPS_ADVERT_VALUES = listOf("none", "share", "prefs")
        val START_SUBCOMMANDS = listOf("ota")
        val REGION_LIST_VALUES = listOf("allowed", "denied")
        val ON_OFF_VALUES = listOf("on", "off")
        val MULTI_ACKS_VALUES = listOf("0", "1")
        val BRIDGE_SOURCE_VALUES = listOf("tx", "rx")

        /** Per MeshCore CLI Reference: all get/set parameters. */
        val GET_SET_PARAMS = listOf(
            "acl", "name", "radio", "tx", "repeat", "lat", "lon",
            "af", "dutycycle", "flood.max", "flood.max.advert", "flood.max.unscoped",
            "int.thresh", "agc.reset.interval",
            "multi.acks", "advert.interval", "flood.advert.interval",
            "guest.password", "allow.read.only",
            "rxdelay", "txdelay", "direct.txdelay",
            "bridge.enabled", "bridge.delay", "bridge.source",
            "bridge.baud", "bridge.secret", "bridge.type",
            "adc.multiplier", "public.key", "prv.key", "role", "freq",
            "path.hash.mode", "loop.detect", "bootloader.ver",
            "owner.info", "radio.rxgain", "radio.fem.rxgain", "cad", "extra.sf", "bridge.channel",
            "pwrmgt.support", "pwrmgt.source", "pwrmgt.bootreason", "pwrmgt.bootmv",
        )

        /** Serial-only params excluded from remote session completions. */
        val SERIAL_ONLY_GET_PARAMS = setOf("prv.key", "acl")
        val SERIAL_ONLY_SET_PARAMS = setOf("freq")
        val PATH_HASH_MODE_VALUES = listOf("0", "1", "2")
        val LOOP_DETECT_VALUES = listOf("off", "minimal", "moderate", "strict")
        val ON_OFF_PARAMS = setOf("repeat", "allow.read.only", "bridge.enabled", "radio.rxgain", "radio.fem.rxgain", "cad")
        val ONE_ARGUMENT_COMMANDS = setOf("session", "login", "log", "powersaving", "clear", "clock", "start")
    }

    var nodeNames: List<String> = emptyList()
        private set

    /** Custom-var keys learned on connection and from each successful local fetch (advisory). */
    var customVarKeys: List<String> = emptyList()
        private set

    fun updateNodeNames(names: List<String>) {
        nodeNames = names.toList()
    }

    fun updateCustomVarKeys(keys: List<String>) {
        customVarKeys = keys.toList()
    }

    fun completions(input: String, isLocal: Boolean, includeSessionCommands: Boolean = true): List<String> {
        val trimmed = SwiftStrings.trimmingWhitespaces(input)
        if (trimmed.isEmpty()) return sorted(availableCommands(isLocal, includeSessionCommands))

        val parts = SwiftStrings.split(trimmed, ' ', omittingEmptySubsequences = false)
        val command = lower(parts[0])
        val endsWithSpace = input.endsWith(" ")

        if (parts.size == 1 && !endsWithSpace) {
            return sorted(availableCommands(isLocal, includeSessionCommands).filter { it.startsWith(command) })
        }

        val argPrefix = if (parts.size > 1) lower(parts[1]) else ""
        return completeArguments(command, parts, argPrefix, endsWithSpace, isLocal)
    }

    private fun completeArguments(
        command: String,
        parts: List<String>,
        prefix: String,
        endsWithSpace: Boolean,
        isLocal: Boolean,
    ): List<String> {
        // A trailing space starts the next argument; otherwise the current one is still being typed.
        val argPosition = if (endsWithSpace) parts.size else parts.size - 1
        return when (command) {
            in ONE_ARGUMENT_COMMANDS -> if (argPosition == 1) completeFirstArg(command, prefix) else emptyList()
            "get" -> completeGet(argPosition, parts, prefix, isLocal)
            "set" -> completeSet(argPosition, parts, prefix, isLocal)
            "sensor" -> if (argPosition == 1 && !isLocal) matching(SENSOR_SUBCOMMANDS, prefix) else emptyList()
            "gps" -> completeGpsArgs(argPosition, parts, prefix)
            "region" -> completeRegionArgs(argPosition, parts, prefix)
            else -> emptyList()
        }
    }

    private fun completeGet(argPosition: Int, parts: List<String>, prefix: String, isLocal: Boolean): List<String> {
        if (argPosition != 1) return emptyList()
        if (isLocal) return localKeyCompletions(LOCAL_GET_KEYS + CUSTOM_VAR_DUMP_KEY, parts)
        return matching(GET_SET_PARAMS.filter { it !in SERIAL_ONLY_GET_PARAMS }, prefix)
    }

    private fun completeSet(argPosition: Int, parts: List<String>, prefix: String, isLocal: Boolean): List<String> {
        if (argPosition == 1) {
            if (isLocal) return localKeyCompletions(LOCAL_SET_KEYS, parts)
            return matching(GET_SET_PARAMS.filter { it !in SERIAL_ONLY_SET_PARAMS }, prefix)
        }
        if (argPosition == 2 && parts.size >= 2) {
            return completeSetValue(lower(parts[1]), if (parts.size > 2) lower(parts[2]) else "")
        }
        return emptyList()
    }

    private fun completeFirstArg(command: String, prefix: String): List<String> = when (command) {
        "session" -> completeSessionArgs(prefix)
        "login" -> sorted(nodeNames.filter { SwiftStrings.hasPrefix(lower(it), prefix) })
        "log" -> matching(LOG_SUBCOMMANDS, prefix)
        "powersaving" -> matching(ON_OFF_VALUES, prefix)
        "clear" -> matching(CLEAR_SUBCOMMANDS, prefix)
        "clock" -> matching(CLOCK_SUBCOMMANDS, prefix)
        "start" -> matching(START_SUBCOMMANDS, prefix)
        else -> emptyList()
    }

    private fun availableCommands(isLocal: Boolean, includeSessionCommands: Boolean): List<String> = buildList {
        addAll(BUILT_IN_COMMANDS)
        if (includeSessionCommands) addAll(SESSION_COMMANDS)
        if (isLocal) {
            addAll(LOCAL_ONLY_COMMANDS)
            addAll(LOCAL_RADIO_COMMANDS)
        } else {
            addAll(REPEATER_COMMANDS)
        }
    }

    private fun completeSessionArgs(prefix: String): List<String> =
        sorted(SESSION_SUBCOMMANDS.filter { it.startsWith(prefix) } + nodeNames.filter { SwiftStrings.hasPrefix(lower(it), prefix) })

    /**
     * Typed keys (lowercase, matched on the folded prefix) merged with learned custom-var keys
     * (matched case-insensitively, suggested verbatim); deduped and sorted.
     */
    private fun localKeyCompletions(typed: List<String>, parts: List<String>): List<String> {
        val lowered = lower(parts.getOrElse(1) { "" })
        val typedMatches = typed.filter { it.startsWith(lowered) }
        val customMatches = customVarKeys.filter { SwiftStrings.hasPrefix(lower(it), lowered) }
        return sorted(SwiftStrings.distinct(typedMatches + customMatches))
    }

    private fun completeSetValue(param: String, prefix: String): List<String> = when (param) {
        "path.hash.mode" -> matching(PATH_HASH_MODE_VALUES, prefix)
        "loop.detect" -> matching(LOOP_DETECT_VALUES, prefix)
        in ON_OFF_PARAMS -> matching(ON_OFF_VALUES, prefix)
        "multi.acks" -> matching(MULTI_ACKS_VALUES, prefix)
        "bridge.source" -> matching(BRIDGE_SOURCE_VALUES, prefix)
        else -> emptyList()
    }

    private fun completeRegionArgs(argPosition: Int, parts: List<String>, prefix: String): List<String> = when {
        argPosition == 1 -> matching(REGION_SUBCOMMANDS, prefix)
        argPosition == 2 && parts.size >= 2 && lower(parts[1]) == "list" ->
            matching(REGION_LIST_VALUES, if (parts.size > 2) lower(parts[2]) else "")
        else -> emptyList()
    }

    private fun completeGpsArgs(argPosition: Int, parts: List<String>, prefix: String): List<String> = when {
        argPosition == 1 -> matching(GPS_SUBCOMMANDS, prefix)
        argPosition == 2 && parts.size >= 2 && lower(parts[1]) == "advert" ->
            matching(GPS_ADVERT_VALUES, if (parts.size > 2) lower(parts[2]) else "")
        else -> emptyList()
    }

    private fun matching(values: List<String>, prefix: String): List<String> = sorted(values.filter { it.startsWith(prefix) })

    private fun sorted(values: List<String>): List<String> = SwiftStrings.sorted(values)

    private fun lower(value: String): String = SwiftStrings.lowercased(value)
}
