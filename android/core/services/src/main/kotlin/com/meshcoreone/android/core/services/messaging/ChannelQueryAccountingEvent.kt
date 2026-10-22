// AndroidOnly: WP-208 Passive persistence receipts for genuine query-failure accounting.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID

enum class ChannelQueryAccountingOutcome { PARKED, TERMINAL_DELETED }

/**
 * Receipt of the stated successful persistence while this flight's token is current.
 * Delivered before releasing recovery, not a notification that the accounting barrier has completed.
 * The passive observer must not block, throw or perform lifecycle work.
 */
data class ChannelQueryAccountingEvent(
    val token: SessionToken,
    val messageID: UUID,
    val pendingID: UUID,
    val failureCount: Long,
    val failureCap: Long,
    val outcome: ChannelQueryAccountingOutcome,
    val queryFailure: Throwable,
)
