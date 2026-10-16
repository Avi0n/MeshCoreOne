package one.meshcore.shared.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import one.meshcore.shared.settings.PersistentLogger

/** Sync gate and the occupant-changed handler shared by channel sync and channel edits. */
internal class ChannelServiceState(slotOccupantChanged: ChannelSlotOccupantChanged, val logger: PersistentLogger?) {
    val syncRetired = CompletableDeferred<Unit>()
    val mutex = Mutex()
    var isSyncing: Boolean = false

    /** Volatile so the handler the host installs after connect is seen by a sync already in flight. */
    @Volatile
    var slotOccupantChanged: ChannelSlotOccupantChanged = slotOccupantChanged
}
