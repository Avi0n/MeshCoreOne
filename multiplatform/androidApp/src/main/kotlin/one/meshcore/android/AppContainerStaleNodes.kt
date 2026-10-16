package one.meshcore.android

import one.meshcore.shared.contact.StaleNodeRemovalResult
import one.meshcore.shared.contact.removeUnfavoritedNodes
import one.meshcore.shared.contact.unfavoritedNodeCount

fun AppContainer.setStaleCleanupThresholdDays(days: Int) {
    staleCleanupSettings.setThresholdDays(days)
    staleCleanupThresholdChanges.launch {
        staleCleanupPrefs.setThresholdDays(days)
        // A settings change runs cleanup immediately, skipping the cooldown (iOS performStaleNodeCleanup(force:)).
        staleNodeCleanupScheduler.performCleanup(force = true)
        staleCleanupSettings.setLastRunSeconds(staleCleanupPrefs.lastRunSeconds())
    }
}
suspend fun AppContainer.unfavoritedCount(): Int {
    val radio = connectionManager.radioId ?: return 0
    val service = connectionManager.services?.contactService ?: return 0
    return unfavoritedNodeCount(radio, service)
}
suspend fun AppContainer.removeUnfavorited(): StaleNodeRemovalResult {
    val radio = connectionManager.radioId
        ?: return StaleNodeRemovalResult(removed = 0, total = 0)
    val service = connectionManager.services?.contactService
        ?: return StaleNodeRemovalResult(removed = 0, total = 0)
    return removeUnfavoritedNodes(radio, service)
}
