package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.ParsedRxLogData
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.settings.PersistentLogger

/** Outcome of feeding one mesh event into [DiscoveredNodeStore]. */
sealed class DiscoveredNodeIngestResult {
    data object Ignored : DiscoveredNodeIngestResult()
    data class Upserted(val isNew: Boolean) : DiscoveredNodeIngestResult()

    /** An inbound advert hop reading was stamped on (or buffered for) its row. */
    data object InboundHopStamped : DiscoveredNodeIngestResult()
}

/**
 * Lookups and clock used by Discover advert ingest. Keep session/store seams out of the pure
 * event→row mapping so unit tests inject fakes without a connection graph.
 *
 * Unknown 0x80 ads no longer call the device here — [ContactService.handleAdvertisement] owns
 * auto-add (and defers mid-sync). Discover rows for auto-added contacts come from
 * [ContactServiceEvent.ContactAdvertisementApplied] after Room is written.
 */
data class DiscoveredNodeIngestDeps(
    val store: DiscoveredNodeStore,
    val lookupLocalContact: suspend (publicKey: ByteArray) -> ContactDto?,
    val nowEpochSeconds: () -> Long,
    /**
     * Posts the first-discovery notification for a 0x8A advert. In manual-add mode 0x8A is the only
     * discovery path there is, so a no-op here means the user is never told a new node appeared.
     */
    val discoveryNotifier: ContactDiscoveryNotifier = ContactDiscoveryNotifier.NoOp,
    /**
     * Mid-sync 0x8A first-seen rows still update Discover. False during the connect-sync window so
     * those first-seen rows do not banner.
     */
    val shouldNotifyDiscovery: () -> Boolean = { true },
    val logger: PersistentLogger? = null,
    val discoverTrace: PersistentLogger? = null,
)

/**
 * Builds a volatile Discover row from a [ContactFrame]. [lastHeardEpochSeconds] is wall-clock "now"
 * (iOS sets `lastHeard = Date()` on every upsert), not the frame's advert timestamp alone.
 */
fun discoveredNodeRecord(
    radioID: Uuid,
    frame: ContactFrame,
    lastHeardEpochSeconds: Long,
    id: Uuid = newDiscoveredNodeId(),
): DiscoveredNodeRecord = DiscoveredNodeRecord(
    id = id,
    radioID = radioID,
    publicKey = frame.publicKey,
    name = frame.name,
    typeRawValue = frame.typeRawValue,
    lastHeardEpochSeconds = lastHeardEpochSeconds,
    lastAdvertTimestamp = frame.lastAdvertTimestamp,
    latitude = frame.latitude,
    longitude = frame.longitude,
    outPathLength = frame.outPathLength,
    outPath = frame.outPath,
)

/** Builds a Discover row from a live [MeshContact] (0x8A new-advert path). */
fun discoveredNodeRecord(
    radioID: Uuid,
    contact: MeshContact,
    lastHeardEpochSeconds: Long,
    id: Uuid = newDiscoveredNodeId(),
): DiscoveredNodeRecord = discoveredNodeRecord(
    radioID = radioID,
    frame = contact.toContactFrame(),
    lastHeardEpochSeconds = lastHeardEpochSeconds,
    id = id,
)

/** Builds a Discover row from a local [ContactDto] (0x80 known-contact advert path). */
fun discoveredNodeRecord(
    radioID: Uuid,
    contact: ContactDto,
    lastHeardEpochSeconds: Long,
    id: Uuid = newDiscoveredNodeId(),
): DiscoveredNodeRecord = DiscoveredNodeRecord(
    id = id,
    radioID = radioID,
    publicKey = contact.publicKey,
    name = contact.name,
    typeRawValue = contact.typeRawValue,
    lastHeardEpochSeconds = lastHeardEpochSeconds,
    lastAdvertTimestamp = contact.lastAdvertTimestamp,
    latitude = contact.latitude,
    longitude = contact.longitude,
    outPathLength = contact.outPathLength,
    outPath = contact.outPath,
)

/**
 * Feeds one [MeshEvent] into the volatile Discover store, mirroring the Discover-relevant branches of
 * iOS `AdvertisementService`:
 * - **0x8A [MeshEvent.NewContact]** — always upsert from the pushed contact frame.
 * - **0x80 [MeshEvent.Advertisement]** — upsert from a local contact when known. Unknown keys are not
 *   device-fetched here (avoids BLE contention with bulk sync and a double `getContact` with
 *   [ContactService]); auto-add is owned by [ContactService.handleAdvertisement], which then yields
 *   [ContactServiceEvent.ContactAdvertisementApplied] for Discover.
 * - **ADVERT [MeshEvent.RxLogData]** — stamp the passively-heard inbound hop count from a flood-routed
 *   advert packet (iOS `RxLogService`). Buffered when the row does not exist yet; drained on the next
 *   upsert. No row is created from an RX-log packet alone.
 */
suspend fun ingestDiscoveredNodeEvent(
    event: MeshEvent,
    radioID: Uuid,
    deps: DiscoveredNodeIngestDeps,
): DiscoveredNodeIngestResult = when (event) {
    is MeshEvent.NewContact -> ingestNewContact(event.contact, radioID, deps)
    is MeshEvent.Advertisement -> ingestAdvertisement(event.publicKey, radioID, deps)
    is MeshEvent.RxLogData -> ingestInboundAdvertHop(event.data, radioID, deps)
    else -> DiscoveredNodeIngestResult.Ignored
}

/**
 * Upserts a Discover row from a Room contact that was just written by 0x80 auto-add / last-advert.
 * Call when [ContactServiceEvent.ContactAdvertisementApplied] is observed.
 */
suspend fun upsertDiscoveredFromLocalContact(
    radioID: Uuid,
    contact: ContactDto,
    store: DiscoveredNodeStore,
    nowEpochSeconds: () -> Long,
): DiscoveredNodeIngestResult {
    val isNew = store.upsert(discoveredNodeRecord(radioID, contact, nowEpochSeconds()))
    return DiscoveredNodeIngestResult.Upserted(isNew)
}

private suspend fun ingestNewContact(
    contact: MeshContact,
    radioID: Uuid,
    deps: DiscoveredNodeIngestDeps,
): DiscoveredNodeIngestResult {
    val pubKeyHex = contact.publicKey.toUppercaseHex()
    deps.discoverTrace?.info("B1 0x8A NEW_ADVERT received key=$pubKeyHex")
    val record = discoveredNodeRecord(
        radioID = radioID,
        contact = contact,
        lastHeardEpochSeconds = deps.nowEpochSeconds(),
    )
    return runDiscoveredIngest(
        onFailure = { error ->
            deps.logger?.error("Error handling new advert event: ${error.message ?: error}")
            deps.discoverTrace?.error("B2 0x8A upsert FAILED key=$pubKeyHex: ${error.message ?: error}")
        },
    ) {
        val isNew = deps.store.upsert(record)
        deps.discoverTrace?.info("B2 0x8A upsert key=$pubKeyHex isNew=$isNew")
        // First sighting only: a repeat advert for a node already in the Discover table is not news.
        // Mid-sync first-seen still upserts so Discover is current; the banner waits until after sync.
        if (isNew && deps.shouldNotifyDiscovery()) {
            deps.discoveryNotifier.notifyNewContact(record.id, record.name, record.contactType)
        }
        DiscoveredNodeIngestResult.Upserted(isNew)
    }
}

private suspend fun ingestAdvertisement(
    publicKey: ByteArray,
    radioID: Uuid,
    deps: DiscoveredNodeIngestDeps,
): DiscoveredNodeIngestResult {
    val local = deps.lookupLocalContact(publicKey) ?: return DiscoveredNodeIngestResult.Ignored
    return runDiscoveredIngest(
        onFailure = { error ->
            deps.logger?.error("Error handling advert event: ${error.message ?: error}")
        },
    ) {
        val isNew = deps.store.upsert(discoveredNodeRecord(radioID, local, deps.nowEpochSeconds()))
        DiscoveredNodeIngestResult.Upserted(isNew)
    }
}

private suspend fun ingestInboundAdvertHop(
    data: ParsedRxLogData,
    radioID: Uuid,
    deps: DiscoveredNodeIngestDeps,
): DiscoveredNodeIngestResult {
    val hop = parseInboundAdvertHop(data) ?: return DiscoveredNodeIngestResult.Ignored
    return runDiscoveredIngest(
        onFailure = { error ->
            deps.logger?.error("Failed to stamp inbound hop count: ${error.message ?: error}")
        },
    ) {
        deps.store.setInboundHopCount(radioID, hop.publicKey, hop.hopCount, hop.advertTimestamp)
        DiscoveredNodeIngestResult.InboundHopStamped
    }
}

/**
 * Collects session [MeshEvent]s into [DiscoveredNodeCollectorInputs.store] for the live
 * [DiscoveredNodeCollectorInputs.radioId]. Cancel and restart when the session or radio
 * partition changes (same rebind pattern as battery collection).
 */
class DiscoveredNodeEventCollector(private val inputs: DiscoveredNodeCollectorInputs) {
    suspend fun collect(events: Flow<MeshEvent>) {
        events.collect { event ->
            val radioID = inputs.radioId() ?: return@collect
            if (event !is MeshEvent.NewContact &&
                event !is MeshEvent.Advertisement &&
                event !is MeshEvent.RxLogData
            ) {
                return@collect
            }
            try {
                ingestDiscoveredNodeEvent(
                    event = event,
                    radioID = radioID,
                    deps = DiscoveredNodeIngestDeps(
                        store = inputs.store,
                        lookupLocalContact = { key -> inputs.lookupLocalContact(radioID, key) },
                        nowEpochSeconds = inputs.nowEpochSeconds,
                        discoveryNotifier = inputs.discoveryNotifier,
                        shouldNotifyDiscovery = inputs.shouldNotifyDiscovery,
                        logger = inputs.logger,
                        discoverTrace = inputs.discoverTrace,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A Room upsert fault must not kill Discover ingest for later adverts.
            }
        }
    }
}

data class DiscoveredNodeCollectorInputs(
    val store: DiscoveredNodeStore,
    val radioId: () -> Uuid?,
    val lookupLocalContact: suspend (radioID: Uuid, publicKey: ByteArray) -> ContactDto?,
    val nowEpochSeconds: () -> Long,
    /** No default: without a real notifier a manual-add radio discovers nodes and tells nobody. */
    val discoveryNotifier: ContactDiscoveryNotifier,
    /** False during the connect-sync window so 0x8A first-seen does not spam banners. */
    val shouldNotifyDiscovery: () -> Boolean = { true },
    val logger: PersistentLogger? = null,
    val discoverTrace: PersistentLogger? = null,
)
