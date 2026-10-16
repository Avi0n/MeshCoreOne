package one.meshcore.shared.channel

import one.meshcore.shared.settings.PersistentLogger

/**
 * Channel (group) management: sync from the radio, create/join/rename, delete/vacate, and the read
 * helpers the chat and channel UI consume. The Kotlin port of the Swift `ChannelService` actor, rebuilt
 * per connection. Sync operations retire with the connection; admission uses one mutex so a
 * concurrent [syncChannels]/[retryFailedChannels] throws instead of interleaving (the coroutine analog of
 * the Swift actor's synchronous flag check). Every operation delegates to the injected session and store.
 *
 * Slot-keying invariant (the channel-message slot trap): rows persist keyed by `(radioID, index)` with
 * both axes frozen; a frame upsert preserves local-only fields unless the secret changed (a different
 * occupant). Live receive trusts the firmware slot and is not reconciled here. Secret derivation and
 * the slot-configured test live in [ChannelSecret] (the wire-primitive slice); this service composes
 * them. `radioID` always arrives from the caller, never minted.
 */
class ChannelService private constructor(parts: ChannelServiceParts) :
    ChannelSyncCommands by parts.sync,
    ChannelSlotReading by parts.reader,
    ChannelEditing by parts.editing,
    ChannelDirectory by parts.directory {
    internal val channelSyncRetired = parts.state.syncRetired

    constructor(
        session: ChannelSession,
        store: ChannelStore,
        rxCache: ChannelListSink = NoOpChannelListSink,
        slotOccupantChanged: ChannelSlotOccupantChanged = NoOpChannelSlotOccupantChanged,
        logger: PersistentLogger? = null,
    ) : this(ChannelServiceParts(session, store, rxCache, slotOccupantChanged, logger))
}

private class ChannelServiceParts(
    session: ChannelSession,
    store: ChannelStore,
    rxCache: ChannelListSink,
    slotOccupantChanged: ChannelSlotOccupantChanged,
    logger: PersistentLogger?,
) {
    val state = ChannelServiceState(slotOccupantChanged, logger)
    private val cache = ChannelRxCacheRefresh(store, rxCache, logger)
    val reader = ChannelSlotReader(session, logger)
    val sync = ChannelSyncEngine(session, store, state, cache, reader)
    val editing = ChannelEditor(session, store, state, cache)
    val directory = ChannelDirectoryStore(store)
}
