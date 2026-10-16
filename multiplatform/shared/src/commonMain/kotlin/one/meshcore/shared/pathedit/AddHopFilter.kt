package one.meshcore.shared.pathedit

/**
 * The sections the Add-Hop picker can narrow to, ported from the iOS `AddHopFilter`. [ALL] shows every
 * section at once (with cross-section dedup); the others show a single section. The UI maps each to its
 * `PathEdit.Filter.*` string resource.
 */
enum class AddHopFilter {
    ALL,
    FAVORITES,
    RECENT,
    DISCOVERED,
}
