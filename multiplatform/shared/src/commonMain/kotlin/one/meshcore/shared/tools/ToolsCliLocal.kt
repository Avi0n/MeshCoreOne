package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.remotenode.CLIOutputType

/**
 * Dispatches a parsed local radio command: confirms or executes and returns true. A destructive command
 * is gated behind a y/n prompt.
 */
internal suspend fun ToolsCliSession.handleLocalCommand(command: CliLocalCommand): Boolean {
    if (local == null) {
        appendOutput(messages.notConnected, CLIOutputType.ERROR)
        return true
    }
    if (command.requiresConfirmation) {
        pendingConfirmation = command
    } else {
        executeLocal(command)
    }
    return true
}

/** Executes a parsed local radio command against the connected radio, appending firmware-parity output. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ToolsCliSession.executeLocal(command: CliLocalCommand) {
    val bridge = local ?: run {
        appendOutput(messages.notConnected, CLIOutputType.ERROR)
        return
    }
    isWaitingForResponse = true
    try {
        if (!runLocalInfo(bridge, command) && !runLocalSetter(bridge, command)) {
            runLocalCustom(bridge, command)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        appendOutput(
            messages.deviceErrorText(error) ?: error.message?.takeIf { it.isNotBlank() } ?: messages.timeout,
            CLIOutputType.ERROR,
        )
    } finally {
        isWaitingForResponse = false
    }
}

private suspend fun ToolsCliSession.runLocalInfo(bridge: ToolsCliLocalBridge, command: CliLocalCommand): Boolean {
    when (command) {
        CliLocalCommand.Clock -> output(LocalCommandOutput.clock(bridge.getTime()))
        CliLocalCommand.ClockSync -> output(LocalCommandOutput.clockSet(bridge.syncClock()))
        CliLocalCommand.Ver -> {
            val capabilities = bridge.deviceCapabilities()
            output("${capabilities.version} (Build: ${capabilities.firmwareBuild})")
        }
        CliLocalCommand.Board -> output(bridge.deviceCapabilities().model)
        is CliLocalCommand.Advert -> {
            bridge.sendAdvert(command.flood)
            output(if (command.flood) LocalCommandOutput.FLOOD_ADVERT else LocalCommandOutput.ZERO_HOP_ADVERT)
        }
        CliLocalCommand.Reboot -> {
            bridge.reboot()
            output(LocalCommandOutput.OK_REBOOTING)
        }
        is CliLocalCommand.GetKey -> runLocalGet(bridge, command.key)
        else -> return false
    }
    return true
}

private suspend fun ToolsCliSession.runLocalSetter(bridge: ToolsCliLocalBridge, command: CliLocalCommand): Boolean {
    when (command) {
        is CliLocalCommand.SetName -> {
            setDeviceName(bridge.setName(command.name))
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetLatitude -> {
            bridge.setLatitude(command.latitude)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetLongitude -> {
            bridge.setLongitude(command.longitude)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetTxPower -> {
            bridge.setTxPower(command.power)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetRadio -> {
            bridge.setRadio(
                command.frequencyMHz,
                command.bandwidthKHz,
                command.spreadingFactor,
                command.codingRate,
            )
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetFrequency -> {
            bridge.setFrequency(command.frequencyMHz)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetMultiAcks -> {
            bridge.setMultiAcks(command.value)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetPathHashMode -> {
            bridge.setPathHashMode(command.mode)
            output(LocalCommandOutput.OK)
        }
        else -> return false
    }
    return true
}

private suspend fun ToolsCliSession.runLocalCustom(bridge: ToolsCliLocalBridge, command: CliLocalCommand) {
    when (command) {
        CliLocalCommand.GetCustomVars -> runLocalCustomVars(bridge, key = null)
        is CliLocalCommand.GetCustomVar -> runLocalCustomVars(bridge, command.key)
        is CliLocalCommand.SetCustomVar -> {
            val ok = bridge.setCustomVar(command.key, command.value)
            output(
                if (ok) {
                    LocalCommandOutput.customVarSet(command.key, command.value)
                } else {
                    LocalCommandOutput.UNKNOWN_CUSTOM_VAR
                },
            )
        }
        CliLocalCommand.Clock,
        CliLocalCommand.ClockSync,
        CliLocalCommand.Ver,
        CliLocalCommand.Board,
        is CliLocalCommand.Advert,
        CliLocalCommand.Reboot,
        is CliLocalCommand.GetKey,
        is CliLocalCommand.SetName,
        is CliLocalCommand.SetLatitude,
        is CliLocalCommand.SetLongitude,
        is CliLocalCommand.SetTxPower,
        is CliLocalCommand.SetRadio,
        is CliLocalCommand.SetFrequency,
        is CliLocalCommand.SetMultiAcks,
        is CliLocalCommand.SetPathHashMode,
        -> Unit
    }
}

internal suspend fun ToolsCliSession.runLocalGet(bridge: ToolsCliLocalBridge, key: CliLocalKey) {
    val text = when (key) {
        CliLocalKey.NAME -> LocalCommandOutput.value(bridge.selfInfo().name)
        CliLocalKey.LAT -> LocalCommandOutput.value(LocalCommandOutput.coordinate(bridge.selfInfo().latitude))
        CliLocalKey.LON -> LocalCommandOutput.value(LocalCommandOutput.coordinate(bridge.selfInfo().longitude))
        CliLocalKey.TX -> LocalCommandOutput.value(bridge.selfInfo().txPower.toString())
        CliLocalKey.RADIO -> LocalCommandOutput.value(LocalCommandOutput.radio(bridge.selfInfo()))
        CliLocalKey.FREQ -> LocalCommandOutput.value(LocalCommandOutput.decimal(bridge.selfInfo().radioFrequency))
        CliLocalKey.PUBLIC_KEY -> LocalCommandOutput.value(LocalCommandOutput.hex(bridge.selfInfo().publicKey))
        CliLocalKey.MULTI_ACKS -> LocalCommandOutput.value(bridge.selfInfo().multiAcks.toString())
        CliLocalKey.PATH_HASH_MODE -> LocalCommandOutput.value(bridge.deviceCapabilities().pathHashMode.toString())
        CliLocalKey.BAT -> LocalCommandOutput.value("${bridge.batteryMillivolts()} mV")
    }
    output(text)
}

/** Fetches all custom vars in one round trip: null dumps them; a key looks up client-side. */
internal suspend fun ToolsCliSession.runLocalCustomVars(bridge: ToolsCliLocalBridge, key: String?) {
    val vars = bridge.getCustomVars()
    completionEngine.updateCustomVarKeys(vars.keys.toList())
    if (key == null) {
        output(LocalCommandOutput.customVarList(vars))
    } else {
        output(vars[key]?.let { LocalCommandOutput.value(it) } ?: LocalCommandOutput.unknownVar(key))
    }
}

internal fun ToolsCliSession.renderParseError(error: CliLocalParseError) {
    val text = when (error) {
        is CliLocalParseError.BadArguments -> when (error.usage) {
            CliLocalUsage.GET -> messages.usageGet
            CliLocalUsage.SET -> messages.usageSet
            CliLocalUsage.SET_RADIO -> messages.usageSetRadio
        }
        CliLocalParseError.ValueOutOfRange -> messages.invalidValue
        CliLocalParseError.InvalidCustomVarToken -> messages.invalidCustomVarToken
    }
    appendOutput(text, CLIOutputType.ERROR)
}
