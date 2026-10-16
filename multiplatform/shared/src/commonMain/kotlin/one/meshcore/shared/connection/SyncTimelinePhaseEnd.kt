package one.meshcore.shared.connection

fun syncContactsPhaseEndLine(count: Int, kind: String): String = "[Sync] Phase end: contacts - $count ($kind)"

fun syncChannelsPhaseEndLine(synced: Int, maxChannels: Int): String =
    "[Sync] Phase end: channels - $synced synced (device capacity: $maxChannels)"

fun syncMessagesPhaseEndLine(count: Int): String = "[Sync] Phase end: messages - $count polled"
