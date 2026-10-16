package one.meshcore.shared.tools

/**
 * A parsed local-session radio command, mirroring the Swift `CLILocalCommand`. Typed reads collapse into
 * one [GetKey] case because each dispatches on the key alone; a custom-var read carries the raw key
 * ([GetCustomVar]) or dumps every var ([GetCustomVars]). Writes stay individual cases because each carries a
 * differently-typed, validated payload.
 */
sealed interface CliLocalCommand {
    data object Clock : CliLocalCommand
    data object ClockSync : CliLocalCommand
    data object Ver : CliLocalCommand
    data object Board : CliLocalCommand
    data class Advert(val flood: Boolean) : CliLocalCommand
    data object Reboot : CliLocalCommand
    data class GetKey(val key: CliLocalKey) : CliLocalCommand
    data class SetName(val name: String) : CliLocalCommand
    data class SetLatitude(val latitude: Double) : CliLocalCommand
    data class SetLongitude(val longitude: Double) : CliLocalCommand
    data class SetTxPower(val power: Int) : CliLocalCommand
    data class SetRadio(
        val frequencyMHz: Double,
        val bandwidthKHz: Double,
        val spreadingFactor: Int,
        val codingRate: Int,
    ) : CliLocalCommand

    data class SetFrequency(val frequencyMHz: Double) : CliLocalCommand
    data class SetMultiAcks(val value: Int) : CliLocalCommand
    data class SetPathHashMode(val mode: Int) : CliLocalCommand
    data object GetCustomVars : CliLocalCommand
    data class GetCustomVar(val key: String) : CliLocalCommand
    data class SetCustomVar(val key: String, val value: String) : CliLocalCommand

    /** Commands that drop the link or move the node off-mesh require a terminal confirm step before executing. */
    val requiresConfirmation: Boolean
        get() = this is Reboot || this is SetRadio || this is SetFrequency

    /** The command text shown in the confirm prompt. */
    val displayName: String
        get() = when (this) {
            is Reboot -> "reboot"
            is SetRadio -> "set radio"
            is SetFrequency -> "set freq"
            else -> ""
        }
}

/**
 * The `get`/`set` key vocabulary for the local session, mirroring the firmware CLI's dotted names verbatim
 * (the Swift `CLILocalKey`).
 */
enum class CliLocalKey(val rawValue: String) {
    NAME("name"),
    LAT("lat"),
    LON("lon"),
    TX("tx"),
    RADIO("radio"),
    FREQ("freq"),
    PUBLIC_KEY("public.key"),
    MULTI_ACKS("multi.acks"),
    PATH_HASH_MODE("path.hash.mode"),
    BAT("bat"),
    ;

    companion object {
        fun fromRaw(raw: String): CliLocalKey? = entries.firstOrNull { it.rawValue == raw }
    }
}
