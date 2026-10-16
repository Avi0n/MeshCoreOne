package one.meshcore.shared.connection

/**
 * The phase of initial sync reported by the sync coordinator, ported from the iOS `SyncPhase`. Used to
 * defer non-essential settings reads until sync is far enough along (see
 * [one.meshcore.shared.settings.canRunSettingsStartupReads]). The sync-activity pill counts only the
 * [CONTACTS] and [CHANNELS] phases, not [MESSAGES].
 */
enum class SyncPhase {
    CONTACTS,
    CHANNELS,
    MESSAGES,
}
