package one.meshcore.shared.transport

/**
 * GATT connection status codes whose failure is the ambiguous edge-of-range auth/encryption signature,
 * the Android analog of `CBError.encryptionTimedOut`: 8 (`GATT_CONN_TIMEOUT`), 22
 * (`GATT_CONN_TERMINATE_LOCAL_HOST`), and 133 (the generic `GATT_ERROR` Android reports for a peer-side
 * bond invalidation). A single one is transient, but a budget dominated by them is how a silently
 * invalidated bond looks attempt-by-attempt, so the manager tallies them toward bond-suspect escalation.
 */
private val ENCRYPTION_TIMEOUT_GATT_STATUSES = setOf(8, 22, 133)

/**
 * GATT statuses that are a dead bond, not a transient timeout. Android analogs of iOS
 * `CBATTError.insufficientAuthentication` / `insufficientEncryption` and `GATT_AUTH_FAIL`.
 * These must not enter the reconnect loop.
 */
private val DEFINITIVE_BOND_GATT_STATUSES = setOf(5, 15, 137)

/**
 * Maps a failed-connect GATT status code to the connect exception the manager classifies: an
 * [EncryptionTimeoutException] for the encryption-timeout-class statuses, or `null` for everything else
 * so the caller keeps the original transient failure. Pure so the status-to-exception mapping is
 * unit-tested without touching Kable.
 */
fun encryptionTimeoutForGattStatus(status: Int): EncryptionTimeoutException? =
    if (status in ENCRYPTION_TIMEOUT_GATT_STATUSES) {
        EncryptionTimeoutException("connect failed with encryption-timeout-class GATT status $status")
    } else {
        null
    }

/** True when [status] is a definitive dead-bond GATT code that must not be retried. */
fun isDefinitiveBondGattStatus(status: Int): Boolean = status in DEFINITIVE_BOND_GATT_STATUSES

/** Prefers a dead-bond exception over an encryption-timeout tally. */
fun classifyGattConnectFailure(status: Int): Exception? = if (isDefinitiveBondGattStatus(status)) {
    BondLostException("connect failed with definitive-bond GATT status $status")
} else {
    encryptionTimeoutForGattStatus(status)
}

/** Drop-time classification for a GATT status, or an ordinary loss when the status is missing. */
fun dropReasonForGattStatus(status: Int?): LinkDropReason = if (status != null && isDefinitiveBondGattStatus(status)) {
    LinkDropReason.BOND_LOST
} else {
    LinkDropReason.CONNECTION_LOST
}
