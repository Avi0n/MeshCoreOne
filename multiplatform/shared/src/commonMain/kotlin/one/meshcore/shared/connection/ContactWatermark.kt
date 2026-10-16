package one.meshcore.shared.connection

import one.meshcore.shared.contact.VContactIdentity

/**
 * How to use a stored contact-sync watermark for one fetch round. Does not rewrite storage — only
 * decides the `since` filter. Mirrors iOS `SyncCoordinator.ContactWatermarkUse`.
 */
sealed interface ContactWatermarkUse {
    /** No successful contact sync stamp yet. */
    data object None : ContactWatermarkUse

    /** Stamp is usable for incremental `since = watermark - 1`. */
    data class Incremental(val watermark: Long) : ContactWatermarkUse

    /**
     * Stamp is implausibly ahead of the phone clock. Fetch with `since == null` this round; leave the
     * stored value alone until write-back of the new max.
     */
    data class Invalid(val stored: Long) : ContactWatermarkUse
}

/** Contact watermark value meaning no contact sync has ever succeeded. */
const val NO_CONTACT_WATERMARK: Long = 0L

/**
 * `since` for a prune-free full contact fetch: the radio reports every contact while local rows
 * it omits are kept. Advert delta and invalid-watermark recovery use this; connect prune uses null.
 */
const val PRUNE_FREE_FULL_FETCH_SINCE: Long = 0L

/** Seconds the incremental `since` filter is rewound so same-second lastmod contacts are not skipped. */
const val INCREMENTAL_SINCE_OVERLAP_SECONDS: Long = 1L

/** Max lead over the phone clock before a stored watermark is treated as unusable for incremental `since`. */
const val CONTACT_WATERMARK_PLAUSIBILITY_SKEW_SECONDS: Long = 2L * 24L * 60L * 60L

/** The `since` filter for an incremental contact fetch from a stored watermark. */
fun incrementalContactSince(watermark: Long): Long = (watermark - INCREMENTAL_SINCE_OVERLAP_SECONDS).coerceAtLeast(0L)

/**
 * Decides whether a stored contact-sync watermark can drive incremental sync. Reference clock is the
 * phone. When the stamp leads the reference by more than two days, return [ContactWatermarkUse.Invalid]
 * so the caller fetches once with `since == null`. Never rewrite the stored stamp downward here.
 */
fun contactWatermarkUse(fromLastContactSync: Long?, referenceNowSeconds: Long): ContactWatermarkUse {
    val raw = fromLastContactSync ?: return ContactWatermarkUse.None
    if (raw == NO_CONTACT_WATERMARK) return ContactWatermarkUse.None
    val upperBound = referenceNowSeconds + CONTACT_WATERMARK_PLAUSIBILITY_SKEW_SECONDS
    if (raw > upperBound) return ContactWatermarkUse.Invalid(raw)
    return ContactWatermarkUse.Incremental(raw)
}

/**
 * Real (non-V-contact) local contact count versus radio [maxContacts]. Offline evictions are
 * invisible to the incremental watermark, so a full table forces a pruning `since == null` fetch.
 * [maxContacts] of 0 or less is unknown and never trips capacity. Mirrors iOS connect at-capacity.
 */
data class ContactTableCapacity(val realContactCount: Int, val maxContacts: Int) {
    val atCapacity: Boolean get() = maxContacts > 0 && realContactCount >= maxContacts
}

fun contactTableCapacity(
    maxContacts: Int,
    localPublicKeys: List<ByteArray>,
    selfPublicKey: ByteArray?,
): ContactTableCapacity {
    var count = localPublicKeys.size
    val vKey = selfPublicKey?.let { VContactIdentity.publicKey(it) }
    if (vKey != null && localPublicKeys.any { it.contentEquals(vKey) }) {
        count -= 1
    }
    return ContactTableCapacity(realContactCount = count, maxContacts = maxContacts)
}
