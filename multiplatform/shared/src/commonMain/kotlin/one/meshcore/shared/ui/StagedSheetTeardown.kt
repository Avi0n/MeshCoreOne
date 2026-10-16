package one.meshcore.shared.ui

import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.navigation.clearPendingChannelLink
import one.meshcore.shared.navigation.clearPendingContactLink
import one.meshcore.shared.navigation.clearPendingHashtagJoin
import one.meshcore.shared.navigation.clearPendingRoomAuth

/**
 * Releases the coordinator state that staged [sheet], for a dismissal that reaches no terminal action —
 * system back, predictive back, a scrim tap. Sheets that carry their own subject release nothing.
 *
 * A staged link lives in a `StateFlow` the shell watches. Leaving it set after a dismissal makes that
 * link un-openable for the rest of the session: tapping it again writes an equal value, the flow
 * conflates it, and the effect that opens the sheet never runs again. Mirrors the explicit
 * Cancel/Done paths, which each call the matching clear.
 */
fun NavigationCoordinator.clearStagingFor(sheet: ShellSheet) {
    when (sheet) {
        is ShellSheet.ConfirmAddContact -> clearPendingContactLink()
        is ShellSheet.JoinChannelLink -> clearPendingChannelLink()
        is ShellSheet.JoinHashtagFromMessage -> clearPendingHashtagJoin()
        is ShellSheet.RoomAuth -> clearPendingRoomAuth()
        else -> Unit
    }
}
