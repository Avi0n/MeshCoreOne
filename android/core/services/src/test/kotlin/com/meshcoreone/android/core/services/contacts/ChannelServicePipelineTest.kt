// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChannelServicePipelineTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.session.ChannelFetchResult
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Service-layer gates for the pipelined channel sync path: #1 correctness with gaps, #2
 * mid-burst disconnect (throw and partial-drain), #4 drop-reconcile, plus serial parity.
 *
 * Gates #2b, #4 and #4b run over [ChannelsMockSession]: the protocol session deliberately refuses to
 * re-read an index whose pipelined reply is still possible (`ConnectionLost`, see the WP-209
 * native cases), so the source's reconcile-succeeds and transport-drop scenarios cannot be
 * produced through it; the service logic under test is identical.
 */
class ChannelServicePipelineTest {
    @TestFactory
    fun pipelineGates(): List<DynamicTest> = channelsCases(
        SUITE,
        "pipelined sync persists non-contiguous configured channels to their own slots" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            withPipelineSession { transport, session ->
                val service = ChannelService(session, store, null, ChannelsRecordingClock())
                val sync = async { service.syncChannels(radioId, 8u, usePipelinedRead = true) }
                channelsWaitUntil("all eight channel reads should be primed") { transport.sentData.size == 9 }
                val configuredIndices = setOf(0, 2, 7)
                for (index in 0 until 8) {
                    if (index in configuredIndices) transport.simulateReceive(channelsChannelInfoPacket(index, "ch$index", channelsSecret(0xAB)))
                    else transport.simulateReceive(channelsChannelInfoPacket(index, "", channelsSecret(0)))
                }
                assertEquals(3, sync.await().channelsSynced)
                val stored = store.fetchChannels(radioId)
                assertEquals(listOf<UByte>(0u, 2u, 7u), stored.map { it.index })
                assertEquals(listOf("ch0", "ch2", "ch7"), stored.map { it.name })
            }
        },
        "a dropped write is reconciled with a serial read and persisted" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            val session = ChannelsMockSession()
            session.setStubbedChannels((0..3).associate { it.toUByte() to ChannelInfo(it.toUByte(), "ch$it", channelsSecret(0xAB)) })
            // Index 2 is never answered in the pipeline (simulated dropped Write Command).
            session.pipelineMissing = setOf(2u)
            val service = ChannelService(session, store, null, ChannelsRecordingClock())

            val result = service.syncChannels(radioId, 4u, usePipelinedRead = true)

            assertEquals(4, result.channelsSynced)
            assertEquals(listOf<UByte>(0u, 1u, 2u, 3u, 2u), session.recordedGetChannelIndices, "reconcile issues a serial read for the dropped index")
            val stored = store.fetchChannels(radioId)
            assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), stored.map { it.index })
            assertEquals("ch2", stored.first { it.index == 2.toUByte() }.name)
        },
        "a transport send failure mid-pipeline throws and persists nothing" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            withPipelineSession { transport, session ->
                val service = ChannelService(session, store, null, ChannelsRecordingClock())
                // appStart was send #1; fail every send from #2 (the first channel read) onward.
                transport.failSends(2)
                assertFails { service.syncChannels(radioId, 4u, usePipelinedRead = true) }
                assertTrue(store.fetchChannels(radioId).isEmpty(), "a hard send failure must not persist a partial channel set")
            }
        },
        "a mid-drain stall persists only read slots and never deletes an unread configured slot" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            store.batchSaveChannels(radioId, SnapshotList.of(ChannelInfo(2u, "keep", channelsSecret(0xCD))), SnapshotList.empty(), null)
            val before = store.fetchChannels(radioId).single()
            assertEquals(2.toUByte(), before.index)
            assertEquals("keep", before.name)
            assertEquals(channelsSecret(0xCD), before.secret)

            val read = listOf(0, 1, 3).map { ChannelInfo(it.toUByte(), "ch$it", channelsSecret(0xAB)) }
            val session = ChannelsMockSession()
            session.setStubbedChannels(read.associateBy { it.index })
            session.pipelineMissing = setOf(2u)
            var failureEntries = 0
            val failure = MeshTransportError.SendFailed("mid-drain reconcile send failed")
            session.script(2u, { failureEntries++; throw failure })
            var commits = 0
            val observedStore = object : ChannelPersisting by store {
                override suspend fun batchSaveChannels(
                    radioId: RadioId, configured: SnapshotList<ChannelInfo>,
                    unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
                ) = store.batchSaveChannels(radioId, configured, unconfiguredIndices, pruneBeyond).also {
                    commits++
                    assertEquals(read, configured.toList())
                    assertTrue(unconfiguredIndices.isEmpty(), "an unread slot must not be classified as empty")
                    assertEquals(4.toUByte(), pruneBeyond)
                }
            }
            val service = ChannelService(session, observedStore, null, ChannelsRecordingClock())

            val result = service.syncChannels(radioId, 4u, usePipelinedRead = true)

            assertEquals(1, failureEntries, "the reconcile failure must actually be entered")
            assertEquals(listOf<UByte>(0u, 1u, 2u, 3u, 2u), session.recordedGetChannelIndices)
            assertEquals(3, result.channelsSynced)
            val error = result.errors.single()
            assertEquals(2.toUByte(), error.index)
            assertEquals(ChannelSyncErrorType.TransportError, error.errorType)
            assertEquals(failure.message, error.description)
            assertFalse(result.circuitBreakerAborted)
            assertEquals(1, commits)
            val stored = store.fetchChannels(radioId)
            assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), stored.map { it.index }, "no mis-indexed rows; the unread index 2 is not deleted")
            assertEquals(before, stored.single { it.index == 2.toUByte() }, "the complete unread row is preserved verbatim")
            for (info in read) {
                val row = stored.single { it.index == info.index }
                assertEquals(info.name, row.name)
                assertEquals(info.secret, row.secret)
            }
        },
        "reconcile circuit breaker opens after consecutive failures and never deletes unread slots" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            // Pre-seed configured rows at every index the pipeline fails to read: 2-4 fail their
            // reconcile read (transport drop), 5-7 are skipped once the breaker opens at 3.
            store.batchSaveChannels(
                radioId, (2..7).map { ChannelInfo(it.toUByte(), "keep$it", channelsSecret(0xCD)) }.let { SnapshotList(it) },
                SnapshotList.empty(), null,
            )
            val session = ChannelsMockSession()
            session.setStubbedChannels((0..1).associate { it.toUByte() to ChannelInfo(it.toUByte(), "ch$it", channelsSecret(0xAB)) })
            session.pipelineMissing = (2..7).map { it.toUByte() }.toSet()
            // Every reconcile read fails as a transport drop: counted toward the breaker, never retried.
            (2..7).forEach { index -> session.script(index.toUByte(), { throw MeshTransportError.SendFailed("simulated send failure") }) }
            val service = ChannelService(session, store, null, ChannelsRecordingClock())

            val result = service.syncChannels(radioId, 8u, usePipelinedRead = true)

            assertEquals(2, result.channelsSynced)
            assertTrue(result.circuitBreakerAborted)
            val transportErrors = result.errors.filter { it.errorType == ChannelSyncErrorType.TransportError }.map { it.index }.sorted()
            val breakerSkips = result.errors.filter { it.errorType == ChannelSyncErrorType.CircuitBreaker }.map { it.index }.sorted()
            assertEquals(listOf<UByte>(2u, 3u, 4u), transportErrors, "the first three missing indices each fail their reconcile read")
            assertEquals(listOf<UByte>(5u, 6u, 7u), breakerSkips, "the open breaker skips the remaining missing indices")
            val stored = store.fetchChannels(radioId)
            assertEquals((0..7).map { it.toUByte() }, stored.map { it.index }, "no unread slot is deleted")
            for (index in 2..7) {
                assertEquals("keep$index", stored.first { it.index == index.toUByte() }.name, "an unread slot is preserved verbatim")
            }
            assertEquals("ch0", stored.first { it.index == 0.toUByte() }.name)
        },
        "serial path (usePipelinedRead: false) still syncs via acknowledged reads" to {
            val radioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            withPipelineSession(writeWithoutResponse = false) { transport, session ->
                val service = ChannelService(session, store, null, ChannelsRecordingClock())
                val sync = async { service.syncChannels(radioId, 2u, usePipelinedRead = false) }
                channelsWaitUntil("first serial read should be sent") { transport.sentData.size == 2 }
                transport.simulateReceive(channelsChannelInfoPacket(0, "ch0", channelsSecret(0xAB)))
                channelsWaitUntil("second serial read should be sent after the first responds") { transport.sentData.size == 3 }
                transport.simulateReceive(channelsChannelInfoPacket(1, "", channelsSecret(0)))

                assertEquals(1, sync.await().channelsSynced)
                assertEquals(listOf<UByte>(0u), store.fetchChannels(radioId).map { it.index })
            }
        },
    )

    @TestFactory
    fun protocolReconcileBoundary(): List<DynamicTest> = channelsNativeCases(
        "pipelined reconcile of a dropped index fails closed through the protocol session and keeps the row" to {
            val radioId: RadioId = channelsRadioId()
            val store = ChannelsInMemoryStore()
            store.batchSaveChannels(radioId, SnapshotList.of(ChannelInfo(2u, "keep", channelsSecret(0xCD))), SnapshotList.empty(), null)
            val before = store.fetchChannels(radioId).single()
            assertEquals(2.toUByte(), before.index)
            assertEquals("keep", before.name)
            assertEquals(channelsSecret(0xCD), before.secret)
            withPipelineSession { transport, session ->
                var drained: ChannelFetchResult? = null
                val reconcileIndices = mutableListOf<UByte>()
                val rejected = mutableListOf<MeshCoreException.ConnectionLost>()
                val observedSession = object : ChannelSessionOps by session {
                    override suspend fun getChannels(indices: List<UByte>): ChannelFetchResult =
                        session.getChannels(indices).also { drained = it }

                    override suspend fun getChannel(index: UByte): ChannelInfo {
                        reconcileIndices += index
                        try {
                            return session.getChannel(index)
                        } catch (failure: MeshCoreException.ConnectionLost) {
                            rejected += failure
                            throw failure
                        }
                    }
                }
                val service = ChannelService(observedSession, store, null, ChannelsRecordingClock())
                val sync = async { service.syncChannels(radioId, 4u, usePipelinedRead = true) }
                channelsWaitUntil("all four reads should be primed") { transport.sentData.size == 5 }
                for (index in listOf(0, 1, 3)) {
                    transport.simulateReceive(channelsChannelInfoPacket(index, "ch$index", channelsSecret(0xAB)))
                }

                val result = sync.await()
                val partial = assertNotNull(drained)
                assertEquals(listOf<UByte>(0u, 1u, 3u), partial.received.map { it.index })
                assertEquals(listOf<UByte>(2u), partial.missing)
                assertEquals(listOf<UByte>(2u), reconcileIndices)
                assertEquals(3, result.channelsSynced)
                assertEquals(listOf<UByte>(2u), result.errors.map { it.index })
                assertEquals(ChannelSyncErrorType.Unknown, result.errors.single().errorType, "ConnectionLost maps like any other session error")
                assertEquals(rejected.single().message, result.errors.single().description)
                assertEquals(listOf(0, 1, 2, 3), transport.sentData.drop(1).map { it[1].toInt() })
                assertEquals(5, transport.sentData.size, "the session refuses the reconcile read before writing")
                val stored = store.fetchChannels(radioId)
                assertEquals(listOf("ch0", "ch1", "keep", "ch3"), stored.map { it.name })
                assertEquals(before, stored.single { it.index == 2.toUByte() })
            }
        },
    )

    private suspend fun CoroutineScope.withPipelineSession(
        writeWithoutResponse: Boolean = true,
        body: suspend CoroutineScope.(MockTransport, MeshCoreSession) -> Unit,
    ) {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(writeWithoutResponse)
        val session = channelsStartedSession(transport, { transport.sentData.size }, transport::simulateReceive)
        try {
            body(transport, session)
        } finally {
            session.stop()
        }
    }

    private companion object {
        const val SUITE = "ChannelServicePipelineTests"
    }
}
