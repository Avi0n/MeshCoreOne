package one.meshcore.shared.room

/**
 * The room admin CLI get/set command surface and its response parsing, ported from the Swift
 * `RoomSettingsViewModel`'s plain-text get/set semantics. These are the read/write shapes the
 * settings view model drives over the admin-gated CLI ([RoomAdminService.sendCommand]/`sendRawCommand`).
 *
 * A room settings write applies a field only when it changed against the value last loaded — hence the
 * [changedRoomAccessCommands]/[changedBehaviorCommands] diffs. A `set` counts as applied only when the room
 * answers `ok` ([isSetOk]); any other answer makes the surface report "some settings failed to apply".
 */
object RoomSettingCommand {
    const val GET_GUEST_PASSWORD: String = "get guest.password"
    const val GET_ALLOW_READ_ONLY: String = "get allow.read.only"
    const val GET_ADVERT_INTERVAL: String = "get advert.interval"
    const val GET_FLOOD_ADVERT_INTERVAL: String = "get flood.advert.interval"
    const val GET_FLOOD_MAX: String = "get flood.max"

    fun setGuestPassword(value: String): String = "set guest.password $value"
    fun setAllowReadOnly(on: Boolean): String = "set allow.read.only ${if (on) ON else OFF}"
    fun setAdvertInterval(minutes: Int): String = "set advert.interval $minutes"
    fun setFloodAdvertInterval(hours: Int): String = "set flood.advert.interval $hours"
    fun setFloodMax(hops: Int): String = "set flood.max $hops"

    /**
     * The guest password parsed from a `get guest.password` response: a status word (`ok`/`error`/`unknown`)
     * means "no password set" and maps to empty; otherwise the value with any `"> "` echo prefix stripped.
     */
    fun parseGuestPassword(response: String): String {
        val trimmed = response.trim()
        if (trimmed.lowercase() in STATUS_WORDS) return ""
        return trimmed.removePrefix(ECHO_PREFIX)
    }

    /** Whether a `get allow.read.only` response reads as on. */
    fun parseAllowReadOnly(response: String): Boolean =
        response.trim().removePrefix(ECHO_PREFIX).trim().lowercase() == ON

    /** Whether a `set …` response is a success acknowledgement. */
    fun isSetOk(response: String): Boolean = response.trim().lowercase() == OK

    /** The ordered `set` commands for a Room Access change: only fields that differ from [original]. */
    fun changedRoomAccessCommands(original: RoomAccessSettings, desired: RoomAccessSettings): List<String> = buildList {
        if (desired.guestPassword != null && desired.guestPassword != original.guestPassword) {
            add(setGuestPassword(desired.guestPassword))
        }
        if (desired.allowReadOnly != null && desired.allowReadOnly != original.allowReadOnly) {
            add(setAllowReadOnly(desired.allowReadOnly))
        }
    }

    /** The ordered `set` commands for a Behavior change: only fields that differ from [original]. */
    fun changedBehaviorCommands(original: RoomBehaviorSettings, desired: RoomBehaviorSettings): List<String> =
        buildList {
            if (desired.advertIntervalMinutes != null &&
                desired.advertIntervalMinutes != original.advertIntervalMinutes
            ) {
                add(setAdvertInterval(desired.advertIntervalMinutes))
            }
            if (desired.floodAdvertIntervalHours != null &&
                desired.floodAdvertIntervalHours != original.floodAdvertIntervalHours
            ) {
                add(setFloodAdvertInterval(desired.floodAdvertIntervalHours))
            }
            if (desired.floodMaxHops != null && desired.floodMaxHops != original.floodMaxHops) {
                add(setFloodMax(desired.floodMaxHops))
            }
        }

    private const val ON = "on"
    private const val OFF = "off"
    private const val OK = "ok"
    private const val ECHO_PREFIX = "> "
    private val STATUS_WORDS = setOf(OK, "error", "unknown", "unknown command")
}

/** Room Access settings snapshot (guest password + read-only), each null until loaded. */
data class RoomAccessSettings(val guestPassword: String? = null, val allowReadOnly: Boolean? = null)

/** Behavior settings snapshot (advert/flood intervals + flood max hops), each null until loaded. */
data class RoomBehaviorSettings(
    val advertIntervalMinutes: Int? = null,
    val floodAdvertIntervalHours: Int? = null,
    val floodMaxHops: Int? = null,
)
