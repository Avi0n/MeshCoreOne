// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockContactService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockChannelService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockMessagePollingService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockAppStateProvider.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.contracts.domain.MessagePollingServiceProtocol
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.ContactMessage
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation

/** Swift `MockContactService`: stubbed result plus recorded `since` arguments. */
internal class MockContactService : ContactServiceProtocol {
    data class SyncContactsInvocation(val radioId: RadioId, val since: Instant?)

    @Volatile var stubbedSyncContactsResult: Result<ContactSyncResult> = Result.success(ContactSyncResult(0, 0u, false))
    private val invocations = java.util.Collections.synchronizedList(ArrayList<SyncContactsInvocation>())
    val syncContactsInvocations: List<SyncContactsInvocation> get() = synchronized(invocations) { invocations.toList() }

    override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult {
        invocations += SyncContactsInvocation(radioId, since)
        return stubbedSyncContactsResult.getOrThrow()
    }

    fun reset() = invocations.clear()
}

/** Swift `MockChannelService`. */
internal class MockChannelService : ChannelServiceProtocol {
    data class SyncChannelsInvocation(val radioId: RadioId, val maxChannels: UByte, val usePipelinedRead: Boolean)
    data class RetryInvocation(val radioId: RadioId, val indices: List<UByte>)

    @Volatile var stubbedSyncChannelsResult: Result<ChannelSyncResult> = Result.success(ChannelSyncResult(0))
    @Volatile var stubbedRetryResult: Result<ChannelSyncResult> = Result.success(ChannelSyncResult(0))
    private val syncs = java.util.Collections.synchronizedList(ArrayList<SyncChannelsInvocation>())
    private val retries = java.util.Collections.synchronizedList(ArrayList<RetryInvocation>())
    val syncChannelsInvocations: List<SyncChannelsInvocation> get() = synchronized(syncs) { syncs.toList() }
    val retryInvocations: List<RetryInvocation> get() = synchronized(retries) { retries.toList() }

    override suspend fun syncChannels(radioId: RadioId, maxChannels: UByte, usePipelinedRead: Boolean): ChannelSyncResult {
        syncs += SyncChannelsInvocation(radioId, maxChannels, usePipelinedRead)
        return stubbedSyncChannelsResult.getOrThrow()
    }

    override suspend fun retryFailedChannels(radioId: RadioId, indices: SnapshotList<UByte>): ChannelSyncResult {
        retries += RetryInvocation(radioId, indices.toList())
        return stubbedRetryResult.getOrThrow()
    }
}

/** Swift `MockMessagePollingService`: stubbed poll, counters and captured handlers. */
internal open class MockMessagePollingService : MessagePollingServiceProtocol {
    @Volatile var stubbedPollAllMessagesResult: Result<Long> = Result.success(0)
    @Volatile var pollAllMessagesCallCount = 0
    @Volatile var waitForPendingHandlersInvocations = 0
    @Volatile var pauseAutoFetchCallCount = 0
    @Volatile var resumeAutoFetchCallCount = 0
    val startAutoFetchRadioIds: MutableList<RadioId> = java.util.Collections.synchronizedList(ArrayList())

    @Volatile var capturedContactMessageHandler: (suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit)? = null
    @Volatile var capturedChannelMessageHandler: (suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit)? = null
    @Volatile var capturedSignedMessageHandler: (suspend (ContactMessage, ContactDTO?) -> Unit)? = null
    @Volatile var capturedCLIMessageHandler: (suspend (ContactMessage, ContactDTO?) -> Unit)? = null

    override suspend fun pollAllMessages(): Long {
        pollAllMessagesCallCount += 1
        return stubbedPollAllMessagesResult.getOrThrow()
    }

    override suspend fun waitForPendingHandlers(timeout: Duration): Boolean {
        waitForPendingHandlersInvocations += 1
        return true
    }

    override suspend fun startAutoFetch(radioId: RadioId) {
        startAutoFetchRadioIds += radioId
    }

    override suspend fun pauseAutoFetch() {
        pauseAutoFetchCallCount += 1
    }

    override suspend fun resumeAutoFetch() {
        resumeAutoFetchCallCount += 1
    }

    override suspend fun setContactMessageHandler(handler: suspend (ContactMessage, ContactDTO?, DeliveryContext) -> Unit) {
        capturedContactMessageHandler = handler
    }

    override suspend fun setChannelMessageHandler(handler: suspend (ChannelMessage, ChannelDTO?, DeliveryContext) -> Unit) {
        capturedChannelMessageHandler = handler
    }

    override suspend fun setSignedMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit) {
        capturedSignedMessageHandler = handler
    }

    override suspend fun setCLIMessageHandler(handler: suspend (ContactMessage, ContactDTO?) -> Unit) {
        capturedCLIMessageHandler = handler
    }

    override suspend fun clearMessageHandlers() {
        capturedContactMessageHandler = null
        capturedChannelMessageHandler = null
        capturedSignedMessageHandler = null
        capturedCLIMessageHandler = null
    }
}

/** Swift `MockAppStateProvider`. */
internal class MockAppStateProvider(@Volatile var stubbedIsInForeground: Boolean = true) : AppStateProvider {
    override suspend fun isInForeground(): Boolean = stubbedIsInForeground
}

/** Swift `OrderTrackingMessagePollingService`: compares the latest recorded wall timestamps. */
internal class OrderTrackingMessagePollingService(private val clock: SyncClock) : MockMessagePollingService() {
    private val lock = Any()
    private var activityEndedTime: Instant? = null
    private var messagePollTime: Instant? = null

    fun recordActivityEnded() {
        synchronized(lock) { activityEndedTime = clock.now() }
    }

    val activityEndedBeforeMessagePoll: Boolean
        get() = synchronized(lock) {
            val ended = activityEndedTime
            val poll = messagePollTime
            ended != null && poll != null && ended < poll
        }

    override suspend fun pollAllMessages(): Long {
        synchronized(lock) { messagePollTime = clock.now() }
        return super.pollAllMessages()
    }
}

/** Swift `DelayingContactService`: holds `syncContacts` until [completeSync]. */
internal class DelayingContactService : ContactServiceProtocol {
    private val started = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()

    suspend fun waitForSyncStart() = started.await()
    fun completeSync() {
        release.complete(Unit)
    }

    override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult {
        started.complete(Unit)
        release.await()
        return ContactSyncResult(0, 0u, false)
    }
}

/** Swift `GatedContactService`: suspends inside `syncContacts` until [release]. */
internal class GatedContactService : ContactServiceProtocol {
    private val started = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    suspend fun waitForSyncStart() = started.await()
    fun release() {
        gate.complete(Unit)
    }

    override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult {
        started.complete(Unit)
        gate.await()
        return ContactSyncResult(0, 0u, true)
    }
}

/** Swift `DelayingChannelService`: blocks in `syncChannels` until cancelled. */
internal class DelayingChannelService : ChannelServiceProtocol {
    private val started = CompletableDeferred<Unit>()
    suspend fun waitForSyncStart() = started.await()

    override suspend fun syncChannels(radioId: RadioId, maxChannels: UByte, usePipelinedRead: Boolean): ChannelSyncResult {
        started.complete(Unit)
        awaitCancellation()
    }

    override suspend fun retryFailedChannels(radioId: RadioId, indices: SnapshotList<UByte>): ChannelSyncResult = ChannelSyncResult(0)
}
