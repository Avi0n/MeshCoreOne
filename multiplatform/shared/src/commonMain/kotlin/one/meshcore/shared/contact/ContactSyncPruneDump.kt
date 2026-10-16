package one.meshcore.shared.contact

import one.meshcore.shared.model.toLowercaseHex

/** Bytes of the public-key prefix iOS interpolates in the full-sync prune deleting line. */
private const val PRUNE_KEY_PREFIX_BYTES: Int = 4

/** iOS `ContactService.syncContacts` full-sync prune summary. */
fun fullSyncPruneSummaryLine(orphanCount: Int, deviceCount: Int, localCount: Int): String =
    "Full sync prune: $orphanCount local contact(s) not found on device " +
        "(device has $deviceCount, local has $localCount)"

/** iOS `ContactService.syncContacts` per-orphan deleting line. */
fun fullSyncPruneDeletingLine(
    name: String,
    publicKey: ByteArray,
    isFavorite: Boolean,
    typeRawValue: Int,
    lastModified: Long,
): String {
    val prefixLen = minOf(PRUNE_KEY_PREFIX_BYTES, publicKey.size)
    val keyPrefix = publicKey.copyOfRange(0, prefixLen).toLowercaseHex()
    return "Full sync prune: deleting '$name' [$keyPrefix…] " +
        "(favorite=$isFavorite, type=$typeRawValue, lastModified=$lastModified)"
}
