package one.meshcore.shared.contact

/**
 * Outcome of an advert-driven contact delta GET_CONTACTS round. Mirrors iOS
 * `AdvertContactSyncOutcome`.
 *
 * - [SYNCED] — the radio was asked and the round committed.
 * - [BUSY] — another sync holds the claim; requeue and retry.
 * - [FAILED] — the exchange failed; requeue until the failure cap.
 * - [NOT_READY] — no completed full fetch yet; drop drained keys.
 */
enum class AdvertContactSyncOutcome {
    SYNCED,
    BUSY,
    FAILED,
    NOT_READY,
}
