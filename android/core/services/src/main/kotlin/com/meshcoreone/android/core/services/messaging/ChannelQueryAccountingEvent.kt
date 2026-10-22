// AndroidOnly: WP-208 Causal observation of genuine query-failure accounting after persistence.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.SessionToken
import java.util.UUID

enum class ChannelQueryAccountingOutcome { PARKED, TERMINAL_DELETED }

/** An actual thrown query outcome, emitted after its stated persistence completes, before recovery is released. */
data class ChannelQueryAccountingEvent(
    val token: SessionToken,
    val messageID: UUID,
    val pendingID: UUID,
    val failureCount: Long,
    val failureCap: Long,
    val outcome: ChannelQueryAccountingOutcome,
    val queryFailure: Throwable,
)
