package one.meshcore.shared.navigation

/**
 * One launcher-hidden Assistant inventory row for Send Advert reach.
 * iOS `AdvertReach` is the same pair; a stray launcher pin must not transmit.
 */
data class SendAdvertShortcutSpec(val shortcutId: String, val flood: Boolean, val url: String)

const val SEND_ADVERT_SHORTCUT_ID_PREFIX: String = "send-advert-"

const val SEND_ADVERT_ZERO_HOP_URL: String = "meshcore://advert?reach=zeroHop"

const val SEND_ADVERT_FLOOD_URL: String = "meshcore://advert?reach=flood"

/** Result of a Shortcuts Send Advert after confirm. Mirrors iOS `SendAdvertIntent.perform`. */
sealed interface SendAdvertShortcutOutcome {
    data object Sent : SendAdvertShortcutOutcome
    data object NotConnected : SendAdvertShortcutOutcome
    data object Failed : SendAdvertShortcutOutcome
}

/** The two reach targets Assistant can bind, defaulting first to zero-hop. */
fun sendAdvertShortcutSpecs(): List<SendAdvertShortcutSpec> = listOf(
    SendAdvertShortcutSpec(
        shortcutId = SEND_ADVERT_SHORTCUT_ID_PREFIX + "zero-hop",
        flood = false,
        url = SEND_ADVERT_ZERO_HOP_URL,
    ),
    SendAdvertShortcutSpec(
        shortcutId = SEND_ADVERT_SHORTCUT_ID_PREFIX + "flood",
        flood = true,
        url = SEND_ADVERT_FLOOD_URL,
    ),
)
