package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid

/**
 * Re-reads a contact from the radio and overwrites the local path fields. Mirrors iOS
 * `AdvertisementService.handlePathUpdatedEvent` after a `pathUpdate` push.
 *
 * @return true when a device contact was found and persisted.
 */
suspend fun ContactService.refreshPathFromDevice(radioID: Uuid, publicKey: ByteArray): Boolean =
    contactPathRefresh.refreshPathFromDevice(radioID, publicKey)

/**
 * Stamps phone-clock recency after a successful ping. Mirrors iOS `PingHelper` calling
 * `touchContactHeard` once the zero-hop TRACE returns.
 */
suspend fun ContactService.markHeard(radioID: Uuid, publicKey: ByteArray) =
    contactHeardRecorder.markHeard(radioID, publicKey)

/**
 * Phone-clock recency for a contact heard on air, plus the matching Discover row (create if
 * missing). Mirrors iOS `PersistenceStore.touchContactHeard`.
 */
internal suspend fun ContactService.stampHeardOnAir(
    radioID: Uuid,
    publicKey: ByteArray,
    atEpochSeconds: Long,
): Boolean = contactHeardRecorder.stampHeardOnAir(radioID, publicKey, atEpochSeconds)
