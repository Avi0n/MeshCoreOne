package one.meshcore.shared.cli

import one.meshcore.shared.tools.CliLocalKey

/**
 * Tab-completion engine for the CLI terminal, ported verbatim from the Swift `CLICompletionEngine`.
 *
 * Pure logic: the only mutable state is [nodeNames] (repeater/room contact names, injected by the
 * consumer). Given a raw input line it returns the applicable completions.
 *
 * Matching is case-INSENSITIVE ([startsWith] on lowercased text); the final ordering is a plain
 * [sorted] using Kotlin's natural [String] order — code-point / case-SENSITIVE, matching Swift's
 * default `Array<String>.sorted()` (ASCII uppercase before lowercase). Do not swap in a
 * locale-aware or case-insensitive collation.
 */
class CLICompletionEngine {
    // MARK: - Node Names

    var nodeNames: List<String> = emptyList()
        private set

    fun updateNodeNames(names: List<String>) {
        nodeNames = names
    }

    // MARK: - Custom Var Keys

    /**
     * Custom-var keys learned from each successful local custom-var fetch. Advisory only; offered on bare
     * `get`/`set` on a local session.
     */
    var customVarKeys: List<String> = emptyList()
        private set

    fun updateCustomVarKeys(keys: List<String>) {
        customVarKeys = keys
    }

    private val argumentCompletions = ArgumentCompletions()

    // MARK: - Completion Logic

    fun completions(input: String, isLocal: Boolean, includeSessionCommands: Boolean = true): List<String> {
        val trimmed = input.trim()

        // Empty or just spaces - return all applicable commands
        if (trimmed.isEmpty()) {
            return availableCommands(isLocal, includeSessionCommands).sorted()
        }

        val parts = trimmed.split(" ")
        val command = parts[0].lowercase()

        return if (parts.size == 1 && !input.endsWith(" ")) {
            // Single word - complete command name
            availableCommands(isLocal, includeSessionCommands)
                .filter { it.startsWith(command) }
                .sorted()
        } else {
            // Command with space - complete arguments
            val argPrefix = if (parts.size > 1) parts[1].lowercase() else ""
            completeArguments(command, parts, argPrefix, input.endsWith(" "), isLocal)
        }
    }

    private fun completeArguments(
        command: String,
        parts: List<String>,
        prefix: String,
        endsWithSpace: Boolean,
        isLocal: Boolean,
    ): List<String> {
        // Determine which argument position we're completing.
        // parts.size includes command, so parts.size - 1 = number of args started.
        // If endsWithSpace, we're starting a NEW argument (position = parts.size).
        // If !endsWithSpace, we're still typing the CURRENT argument (position = parts.size - 1).
        val argPosition = if (endsWithSpace) parts.size else parts.size - 1

        return when (command) {
            // 1-arg commands: only complete when argPosition == 1
            "session", "login", "log", "powersaving", "clear", "clock", "start" ->
                if (argPosition == 1) completeFirstArg(command, prefix) else emptyList()

            "get" ->
                if (argPosition == 1) {
                    if (isLocal) {
                        localKeyCompletions(localGetKeys + CUSTOM_VAR_DUMP_KEY, parts)
                    } else {
                        getSetParams
                            .filter { it !in serialOnlyGetParams }
                            .filter { it.startsWith(prefix) }
                            .sorted()
                    }
                } else {
                    emptyList()
                }

            "set" -> completeSetArgs(argPosition, parts, prefix, isLocal)
            // The `sensor` namespace targets a remote node; it is not a local command.
            "sensor" -> if (argPosition == 1 && !isLocal) {
                sensorSubcommands.filter { it.startsWith(prefix) }.sorted()
            } else {
                emptyList()
            }
            "gps" -> argumentCompletions.completeGpsArgs(argPosition, parts, prefix)
            "region" -> argumentCompletions.completeRegionArgs(argPosition, parts, prefix)
            else -> emptyList()
        }
    }

    private fun completeSetArgs(argPosition: Int, parts: List<String>, prefix: String, isLocal: Boolean): List<String> =
        when {
            argPosition == 1 ->
                if (isLocal) {
                    localKeyCompletions(localSetKeys, parts)
                } else {
                    getSetParams
                        .filter { it !in serialOnlySetParams }
                        .filter { it.startsWith(prefix) }
                        .sorted()
                }

            argPosition == 2 && parts.size >= 2 ->
                argumentCompletions.completeSetValue(
                    param = parts[1].lowercase(),
                    prefix = if (parts.size > 2) parts[2].lowercase() else "",
                )

            else -> emptyList()
        }

    private fun completeFirstArg(command: String, prefix: String): List<String> = when (command) {
        "session" -> argumentCompletions.completeSessionArgs(prefix)
        "login" -> nodeNames.filter { it.lowercase().startsWith(prefix) }.sorted()
        "log" -> logSubcommands.filter { it.startsWith(prefix) }.sorted()
        "powersaving" -> onOffValues.filter { it.startsWith(prefix) }.sorted()
        "clear" -> clearSubcommands.filter { it.startsWith(prefix) }.sorted()
        "clock" -> clockSubcommands.filter { it.startsWith(prefix) }.sorted()
        "start" -> startSubcommands.filter { it.startsWith(prefix) }.sorted()
        else -> emptyList()
    }

    private fun availableCommands(isLocal: Boolean, includeSessionCommands: Boolean): List<String> {
        val commands = builtInCommands.toMutableList()

        if (includeSessionCommands) {
            commands += sessionCommands
        }

        if (isLocal) {
            commands += localOnlyCommands
            commands += localRadioCommands
        } else {
            commands += repeaterCommands
        }

        return commands
    }

    /**
     * Merges typed keys (lowercase, matched on the folded prefix) with learned custom-var keys (matched
     * case-insensitively, suggested verbatim) for bare `get`/`set` on a local session; result deduped and
     * sorted. Reads the raw argument from [parts] so a mixed-case custom key is offered unchanged.
     */
    private fun localKeyCompletions(typed: List<String>, parts: List<String>): List<String> {
        val lowered = (if (parts.size > 1) parts[1] else "").lowercase()
        val typedMatches = typed.filter { it.startsWith(lowered) }
        val customMatches = customVarKeys.filter { it.lowercase().startsWith(lowered) }
        return (typedMatches + customMatches).toSet().sorted()
    }

    private inner class ArgumentCompletions {
        fun completeSessionArgs(prefix: String): List<String> {
            val suggestions = sessionSubcommands.filter { it.startsWith(prefix) }.toMutableList()
            suggestions += nodeNames.filter { it.lowercase().startsWith(prefix) }
            return suggestions.sorted()
        }

        fun completeSetValue(param: String, prefix: String): List<String> = when (param) {
            "path.hash.mode" -> pathHashModeValues.filter { it.startsWith(prefix) }.sorted()
            "loop.detect" -> loopDetectValues.filter { it.startsWith(prefix) }.sorted()
            "repeat", "allow.read.only", "bridge.enabled", "radio.rxgain", "radio.fem.rxgain", "cad" ->
                onOffValues.filter { it.startsWith(prefix) }.sorted()

            "multi.acks" -> multiAcksValues.filter { it.startsWith(prefix) }.sorted()
            "bridge.source" -> bridgeSourceValues.filter { it.startsWith(prefix) }.sorted()
            else -> emptyList()
        }

        fun completeRegionArgs(argPosition: Int, parts: List<String>, prefix: String): List<String> = when {
            argPosition == 1 -> regionSubcommands.filter { it.startsWith(prefix) }.sorted()
            argPosition == 2 && parts.size >= 2 && parts[1].lowercase() == "list" -> {
                val valuePrefix = if (parts.size > 2) parts[2].lowercase() else ""
                regionListValues.filter { it.startsWith(valuePrefix) }.sorted()
            }

            else -> emptyList()
        }

        fun completeGpsArgs(argPosition: Int, parts: List<String>, prefix: String): List<String> = when {
            argPosition == 1 -> gpsSubcommands.filter { it.startsWith(prefix) }.sorted()
            argPosition == 2 && parts.size >= 2 && parts[1].lowercase() == "advert" -> {
                val valuePrefix = if (parts.size > 2) parts[2].lowercase() else ""
                gpsAdvertValues.filter { it.startsWith(valuePrefix) }.sorted()
            }

            else -> emptyList()
        }
    }

    // MARK: - Command Definitions

    private companion object {
        val builtInCommands = listOf(
            "help",
            "clear",
        )

        // App-CLI session management; the node CLI is single-session and passes
        // these straight to firmware, which has no such commands.
        val sessionCommands = listOf(
            "session",
            "logout",
        )

        val localOnlyCommands = listOf(
            "login",
            "nodes",
            "channels",
        )

        // Local-session radio commands interpreted app-side. Offered only on local sessions so the repeater
        // vocabulary and this one never leak into each other.
        val localRadioCommands = listOf(
            "advert", "advert.zerohop", "board", "clock", "floodadv", "get", "reboot", "set", "ver",
        )

        val localGetKeys = CliLocalKey.entries.map { it.rawValue }

        /** The `get custom` dump verb, offered alongside typed keys on a local `get`. */
        const val CUSTOM_VAR_DUMP_KEY = "custom"

        /**
         * Every typed key the parser's `set` switch accepts: all cases minus the read-only
         * `public.key`/`bat`. Derived so it can't drift from the enum.
         */
        val localSetKeys = CliLocalKey.entries
            .filter { it != CliLocalKey.PUBLIC_KEY && it != CliLocalKey.BAT }
            .map { it.rawValue }

        // Per MeshCore CLI Reference - commands available via remote session
        val repeaterCommands = listOf(
            "ver", "board", "clock", "clkreboot",
            "neighbors", "get", "set", "sensor", "password",
            "log", "reboot", "room.post", "advert", "advert.zerohop", "setperm", "tempradio", "neighbor.remove",
            "region", "gps", "powersaving", "clear", "discover.neighbors",
            "start",
        )

        val sessionSubcommands = listOf("list", "local")

        val sensorSubcommands = listOf("get", "list", "set")

        val logSubcommands = listOf("start", "stop", "erase")

        val clearSubcommands = listOf("stats")

        val clockSubcommands = listOf("sync")

        // Per MeshCore CLI Reference - region subcommands
        val regionSubcommands = listOf(
            "load", "get", "put", "remove", "allowf", "denyf", "home", "default", "save", "list", "def",
        )

        // Per MeshCore CLI Reference - gps subcommands
        val gpsSubcommands = listOf("on", "off", "sync", "setloc", "advert")

        val gpsAdvertValues = listOf("none", "share", "prefs")

        val startSubcommands = listOf("ota")

        val regionListValues = listOf("allowed", "denied")

        val onOffValues = listOf("on", "off")

        val multiAcksValues = listOf("0", "1")

        val bridgeSourceValues = listOf("tx", "rx")

        // Per MeshCore CLI Reference - all get/set parameters
        val getSetParams = listOf(
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

        // Serial-only params excluded from remote session completions
        val serialOnlyGetParams = setOf("prv.key", "acl")
        val serialOnlySetParams = setOf("freq")

        val pathHashModeValues = listOf("0", "1", "2")

        val loopDetectValues = listOf("off", "minimal", "moderate", "strict")
    }
}
