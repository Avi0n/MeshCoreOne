package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

internal fun ContactService.clampedPhoneClockTimestamp(stamp: Long): Long =
    contactHeardRecorder.clampedPhoneClockTimestamp(stamp)

/**
 * After a successful incremental round, escalate to a prune-free full refetch when keys that
 * were unknown at drain still have no row. Mirrors iOS `escalateMissingUnknownKeys`.
 */
internal suspend fun ContactService.escalateMissingUnknownKeys(unknownAtStart: List<ByteArray>, radioID: Uuid?) =
    advertDeltaFollowUp.escalateMissingUnknownKeys(unknownAtStart, radioID)

/**
 * Upserts Discover from the post-GET_CONTACTS contact rows for drained 0x80 keys, then
 * announces only keys this round actually inserted. Mirrors iOS `reconcile`. Hear-time
 * lastHeard dual-write can fail without blocking this pass, so Discover still gets radio
 * name/coords instead of depending on a host collector.
 */
internal suspend fun ContactService.reconcileAdvertDeltaDiscover(
    drainedAdvert: List<ByteArray>,
    unknownAtStart: List<ByteArray>,
    radioID: Uuid?,
) = advertDeltaFollowUp.reconcileAdvertDeltaDiscover(drainedAdvert, unknownAtStart, radioID)

/**
 * Attaches incoming DMs that landed before their sender's contact existed. Runs after a
 * successful delta so a snapshot failure still links orphans against every drained key
 * that now has a row. Mirrors iOS `adoptOrphanedMessages`.
 */
internal suspend fun ContactService.adoptOrphanedMessages(drainedAdvert: List<ByteArray>, radioID: Uuid?) =
    advertDeltaFollowUp.adoptOrphanedMessages(drainedAdvert, radioID)

/**
 * Drops rows a mid-round 0x8F deleted if GET_CONTACTS re-saved them. Uses
 * [ContactStore.deleteContactIfUnreferenced] so a DM attached after the re-save is kept.
 */
internal suspend fun ContactService.rollBackContactsDeletedDuringSync(radioID: Uuid?) =
    advertDeltaFollowUp.rollBackContactsDeletedDuringSync(radioID)
