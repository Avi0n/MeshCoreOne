// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceReprocessTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PayloadType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Test

class RxLogServiceReprocessTest {
    private fun case(name: String, body: suspend () -> Unit): DynamicTest =
        DynamicTest.dynamicTest(if (name.startsWith("WP-212::")) name else "RxLogServiceReprocessTests::$name()") {
            runBlocking { body() }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Restart readiness distinguishes the canceled subscriber from its replacement`() = runTest {
        RxLogSvcHarness(scope = backgroundScope).use { h ->
            h.service.startEventMonitoring(rxLogSvcRadio())
            runCurrent()
            assertEquals(1, h.session.subscriberCount)
            assertEquals(1L, h.session.registrationCount)

            val second = rxLogSvcRadio()
            h.service.startEventMonitoring(second)
            assertEquals(1, h.session.subscriberCount, "the old readiness condition still sees the canceled first monitor")
            assertEquals(1L, h.session.registrationCount, "the replacement has not registered yet")

            runCurrent()
            assertEquals(2L, h.session.registrationCount)
            assertEquals(1, h.session.subscriberCount)
            h.session.emit(MeshEvent.RxLogData(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01))))
            runCurrent()
            assertEquals(listOf(second), h.store.allEntries().map { it.radioId })

            h.service.stopEventMonitoring()
            runCurrent()
            assertEquals(0, h.session.subscriberCount)
        }
    }

    @TestFactory
    fun cases(): List<DynamicTest> = listOf(
        case("late-decrypted channel entries keep the matching channel index and name") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                val channelZeroSecret = Bytes(ByteArray(16) { 0x99.toByte() })
                val channelThreeSecret = Bytes(ByteArray(16) { 0x42 })
                // Seed the channels first so the monitor's secret load cannot interleave different values.
                h.store.saveChannel(rxLogSvcChannel(radioId, 0, "Public", channelZeroSecret))
                h.store.saveChannel(rxLogSvcChannel(radioId, 3, "Three", channelThreeSecret))
                h.service.startEventMonitoring(radioId)

                val senderTimestamp = 1_700_000_000u
                val payload = rxLogSvcEncryptedChannelPayload(senderTimestamp, "hello", channelThreeSecret)
                val entry = RxLogEntryDTO.fromParsed(
                    radioId, rxLogSvcParsed(PayloadType.GROUP_TEXT, payload), decryptStatus = DecryptStatus.NO_MATCHING_KEY,
                )
                h.store.saveRxLogEntry(entry)

                h.service.updateChannels(
                    secrets = mapOf(0.toUByte() to channelZeroSecret, 3.toUByte() to channelThreeSecret),
                    names = mapOf(0.toUByte() to "Public", 3.toUByte() to "Three"),
                )

                val updated = assertNotNull(h.store.fetchRxLogEntries(radioId, 500).firstOrNull { it.id == entry.id })
                assertEquals(3.toUByte(), updated.channelIndex, "reprocessing must record the channel whose secret matched, not nil")
                assertEquals("Three", updated.channelName, "reprocessing must not fall back to channel 0's name")
                assertEquals(senderTimestamp, updated.senderTimestamp)
                assertEquals(DecryptStatus.SUCCESS, updated.decryptStatus)
            }
        },
        case("coexisting entry stream subscribers each receive every entry") {
            RxLogSvcHarness().use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                val streamA = h.service.entryStream()
                val streamB = h.service.entryStream()
                val receivedA = mutableListOf<RxLogEntryDTO>()
                val receivedB = mutableListOf<RxLogEntryDTO>()
                val taskA = h.scope.launch { streamA.collect { synchronized(receivedA) { receivedA += it } } }
                val taskB = h.scope.launch { streamB.collect { synchronized(receivedB) { receivedB += it } } }

                h.service.process(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01, 0x02, 0x03, 0x04)))

                rxLogSvcWaitUntil("the first subscriber must receive the entry") { synchronized(receivedA) { receivedA.isNotEmpty() } }
                rxLogSvcWaitUntil("the second subscriber must also receive the entry") { synchronized(receivedB) { receivedB.isNotEmpty() } }
                taskA.cancel()
                taskB.cancel()
            }
        },
        case("finishEntryStream ends every subscriber's iteration") {
            RxLogSvcHarness().use { h ->
                val streamA = h.service.entryStream()
                val streamB = h.service.entryStream()
                val iterationA = h.scope.async { streamA.collect {}; true }
                val iterationB = h.scope.async { streamB.collect {}; true }

                h.service.finishEntryStream()

                withTimeout(5_000) {
                    assertTrue(iterationA.await(), "finish must end the first subscriber's loop")
                    assertTrue(iterationB.await(), "finish must end the second subscriber's loop")
                }
            }
        },
        case("WP-212::entries yielded after entryStream returns are delivered even before collection starts") {
            RxLogSvcHarness().use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                val stream = h.service.entryStream()
                h.service.process(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01)))
                h.service.finishEntryStream()
                val received = mutableListOf<RxLogEntryDTO>()
                stream.collect { received += it }
                assertEquals(1, received.size, "registration is synchronous, buffered values survive finish")
                // A subscriber registered after finish completes immediately.
                val late = mutableListOf<RxLogEntryDTO>()
                h.service.entryStream().collect { late += it }
                assertTrue(late.isEmpty())
            }
        },
        case("WP-212::live RX events from the session are processed through the event monitor") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("the monitor subscribes to rxLogData") { h.session.subscriberCount == 1 }
                h.session.emit(MeshEvent.RxLogData(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x09, 0x08))))
                rxLogSvcWaitUntil("the live packet is persisted") { h.store.allEntries().size == 1 }
                val saved = h.store.allEntries().single()
                assertEquals(radioId, saved.radioId)
                assertEquals(DecryptStatus.NOT_APPLICABLE, saved.decryptStatus)
            }
        },
        case("WP-212::a throwing repeat processor is logged and does not end live RX monitoring") {
            val calls = java.util.concurrent.atomic.AtomicInteger()
            RxLogSvcHarness(heardRepeats = RxLogRepeatProcessing { calls.incrementAndGet(); error("repeat store down") }).use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                rxLogSvcWaitUntil("the monitor subscribes to rxLogData") { h.session.subscriberCount == 1 }
                h.session.emit(MeshEvent.RxLogData(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x09, 0x08))))
                rxLogSvcWaitUntil("the first packet is persisted") { h.store.allEntries().size == 1 }
                h.session.emit(MeshEvent.RxLogData(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x07, 0x06))))
                rxLogSvcWaitUntil("monitoring survives and persists the second packet") { h.store.allEntries().size == 2 }
                assertEquals(2, calls.get())
                assertTrue(h.logs.all.any { it.level == DebugLogLevel.ERROR && it.message.contains("heard repeats") })
            }
        },
        case("WP-212::stopEventMonitoring cancels the monitor and releases the session subscription") {
            RxLogSvcHarness().use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                rxLogSvcWaitUntil("the monitor subscribes to rxLogData") { h.session.subscriberCount == 1 }
                h.service.stopEventMonitoring()
                rxLogSvcWaitUntil("cancellation unregisters the session subscription") { h.session.subscriberCount == 0 }
                h.session.emit(MeshEvent.RxLogData(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01))))
                assertTrue(h.store.allEntries().isEmpty(), "no live processing after stop")
            }
        },
        case("WP-212::restarting monitoring replaces the previous subscription instead of duplicating it") {
            RxLogSvcHarness().use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                rxLogSvcWaitUntil("first monitor subscribes") { h.session.subscriberCount == 1 }
                val second = rxLogSvcRadio()
                h.service.startEventMonitoring(second)
                rxLogSvcWaitUntil("only the registered second monitor remains subscribed") {
                    h.session.registrationCount == 2L && h.session.subscriberCount == 1
                }
                h.session.emit(MeshEvent.RxLogData(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01))))
                rxLogSvcWaitUntil("exactly one entry is persisted") { h.store.allEntries().isNotEmpty() }
                assertEquals(listOf(second), h.store.allEntries().map { it.radioId })
            }
        },
        case("WP-212::live channel decode marks success, no matching key, or pending by payload size") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("monitor finished its database secret load") { h.session.subscriberCount == 1 }
                val secret = Bytes(ByteArray(16) { 0x11 })
                h.service.updateChannels(mapOf(5.toUByte() to secret), emptyMap())
                val match = rxLogSvcEncryptedChannelPayload(42u, "hi", secret)
                val unknown = rxLogSvcEncryptedChannelPayload(42u, "hi", Bytes(ByteArray(16) { 0x22 }))
                val entries = h.service.entryStream()
                val received = mutableListOf<RxLogEntryDTO>()
                val collector = h.scope.launch(start = CoroutineStart.UNDISPATCHED) { entries.collect { synchronized(received) { received += it } } }
                h.service.process(rxLogSvcParsed(PayloadType.GROUP_TEXT, match))
                h.service.process(rxLogSvcParsed(PayloadType.GROUP_DATA, unknown))
                h.service.process(rxLogSvcParsed(PayloadType.GROUP_TEXT, Bytes(ByteArray(18))))
                rxLogSvcWaitUntil("three entries streamed") { synchronized(received) { received.size == 3 } }
                collector.cancel()
                val (success, noKey, pending) = synchronized(received) { received.toList() }
                assertEquals(DecryptStatus.SUCCESS, success.decryptStatus)
                assertEquals(5.toUByte(), success.channelIndex)
                assertEquals("Channel 5", success.channelName, "a missing name falls back to Channel <index>")
                assertEquals("hi", success.decodedText)
                assertEquals(42u, success.senderTimestamp)
                assertEquals(DecryptStatus.NO_MATCHING_KEY, noKey.decryptStatus)
                assertEquals(DecryptStatus.PENDING, pending.decryptStatus)
            }
        },
        case("WP-212::decryptEntry uses the stored index fast path and re-attributes through the slow path") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                val secretOne = Bytes(ByteArray(16) { 0x01 })
                val secretTwo = Bytes(ByteArray(16) { 0x02 })
                h.service.updateChannels(
                    mapOf(1.toUByte() to secretOne, 2.toUByte() to secretTwo),
                    mapOf(1.toUByte() to "One", 2.toUByte() to "Two"),
                )
                val payload = rxLogSvcEncryptedChannelPayload(7u, "text", secretTwo)
                val stale = RxLogEntryDTO.fromParsed(
                    radioId, rxLogSvcParsed(PayloadType.GROUP_TEXT, payload),
                    channelIndex = 1u, channelName = "Stale", decryptStatus = DecryptStatus.SUCCESS,
                )
                val reattributed = h.service.decryptEntry(stale)
                assertEquals(2.toUByte(), reattributed.channelIndex)
                assertEquals("Two", reattributed.channelName)
                assertEquals("text", reattributed.decodedText)
                val fast = h.service.decryptEntry(stale.copy(channelIndex = 2u, channelName = "Kept"))
                assertEquals("Kept", fast.channelName, "fast path keeps the stored attribution")
                assertEquals("text", fast.decodedText)
                assertEquals(listOf("text", "text"), h.service.decodedEntries(listOf(stale, stale)).map { it.decodedText })
            }
        },
        case("WP-212::noMatchingKey reprocess skips entries older than sixty seconds and feeds heard repeats") {
            val repeats = mutableListOf<RxLogEntryDTO>()
            RxLogSvcHarness(heardRepeats = { synchronized(repeats) { repeats += it } }).use { h ->
                val radioId = rxLogSvcRadio()
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("monitor finished its database secret load") { h.session.subscriberCount == 1 }
                val secret = Bytes(ByteArray(16) { 0x33 })
                val payload = rxLogSvcEncryptedChannelPayload(9u, "late", secret)
                val fresh = RxLogEntryDTO.fromParsed(radioId, rxLogSvcParsed(PayloadType.GROUP_TEXT, payload),
                    decryptStatus = DecryptStatus.NO_MATCHING_KEY)
                val old = fresh.copy(id = java.util.UUID.randomUUID(), receivedAt = fresh.receivedAt.minusSeconds(120))
                h.store.seedEntry(fresh)
                h.store.seedEntry(old)
                h.service.updateChannels(mapOf(0.toUByte() to secret), mapOf(0.toUByte() to "Zero"))
                assertEquals(DecryptStatus.SUCCESS, h.store.entry(fresh.id)?.decryptStatus)
                assertEquals(DecryptStatus.NO_MATCHING_KEY, h.store.entry(old.id)?.decryptStatus)
                assertEquals(listOf("late"), synchronized(repeats) { repeats.map { it.decodedText } })
            }
        },
        case("WP-212::overlapping noMatchingKey reprocess calls are reentrancy guarded") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("monitor finished its database secret load") { h.session.subscriberCount == 1 }
                val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
                h.store.beforeDecryptStatusFetch = { gate.await() }
                val secrets = mapOf(0.toUByte() to Bytes(ByteArray(16)))
                val first = h.scope.async { h.service.updateChannels(secrets, emptyMap()) }
                rxLogSvcWaitUntil("first reprocess is fetching") { h.store.decryptStatusFetchCount == 1 }
                h.service.updateChannels(secrets, emptyMap())
                assertEquals(1, h.store.decryptStatusFetchCount, "the overlapping call returns without fetching")
                gate.complete(Unit)
                first.await()
                h.service.updateChannels(secrets, emptyMap())
                assertEquals(2, h.store.decryptStatusFetchCount, "the guard resets after the first call finishes")
            }
        },
        case("WP-212::contact names match stored and sender prefixes in either direction") {
            RxLogSvcHarness().use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                h.service.updateContactNames(mapOf(Bytes.of(0xAB, 0xCD, 0xEF) to "Alice"))
                h.service.process(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01), senderPubkeyPrefix = Bytes.of(0xAB)))
                h.service.process(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x02),
                    senderPubkeyPrefix = Bytes.of(0xAB, 0xCD, 0xEF, 0x01)))
                h.service.process(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x03), senderPubkeyPrefix = Bytes.of(0xAC)))
                val names = h.store.allEntries().map { it.fromContactName }
                assertEquals(listOf("Alice", "Alice", null), names)
            }
        },
        case("WP-212::store save failures are logged and the entry is still streamed") {
            RxLogSvcHarness().use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                h.store.saveFailure = IllegalStateException("disk full")
                val stream = h.service.entryStream()
                h.service.process(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01)))
                h.service.finishEntryStream()
                val received = mutableListOf<RxLogEntryDTO>()
                stream.collect { received += it }
                assertEquals(1, received.size)
                assertTrue(h.logs.all.any { it.message == "Failed to save RX log entry: disk full" })
            }
        },
        case("WP-212::cancellation thrown by the store propagates out of process and is not logged") {
            RxLogSvcHarness().use { h ->
                h.service.startEventMonitoring(rxLogSvcRadio())
                h.store.saveFailure = kotlinx.coroutines.CancellationException("torn down")
                val failure = runCatchingCancellation { h.service.process(rxLogSvcParsed(PayloadType.ADVERT, Bytes.of(0x01))) }
                assertTrue(failure is kotlinx.coroutines.CancellationException)
                assertTrue(h.logs.all.none { it.message.startsWith("Failed to save") })
            }
        },
        case("WP-212::updateChannels from a channel list rejects duplicate slot indices") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                val duplicate = listOf(
                    rxLogSvcChannel(radioId, 1, "A", Bytes(ByteArray(16))),
                    rxLogSvcChannel(radioId, 1, "B", Bytes(ByteArray(16))),
                )
                val failure = runCatchingCancellation { h.service.updateChannels(duplicate) }
                assertTrue(failure is IllegalArgumentException)
            }
        },
        case("WP-212::loadExistingEntries returns newest 500 entries re-decrypted and clearEntries empties the radio") {
            RxLogSvcHarness().use { h ->
                val radioId = rxLogSvcRadio()
                val secret = Bytes(ByteArray(16) { 0x44 })
                h.store.saveChannel(rxLogSvcChannel(radioId, 0, "Public", secret))
                h.service.startEventMonitoring(radioId)
                rxLogSvcWaitUntil("monitor subscribed after loading secrets") { h.session.subscriberCount == 1 }
                val payload = rxLogSvcEncryptedChannelPayload(1u, "stored", secret)
                val base = RxLogEntryDTO.fromParsed(radioId, rxLogSvcParsed(PayloadType.GROUP_TEXT, payload),
                    decryptStatus = DecryptStatus.SUCCESS, channelIndex = 0u)
                repeat(501) { h.store.seedEntry(base.copy(id = java.util.UUID.randomUUID(), receivedAt = base.receivedAt.plusMillis(it.toLong()))) }
                val loaded = h.service.loadExistingEntries()
                assertEquals(500, loaded.size)
                assertTrue(loaded.all { it.decodedText == "stored" })
                assertTrue(loaded.zipWithNext().all { (a, b) -> a.receivedAt >= b.receivedAt })
                h.service.clearEntries()
                assertTrue(h.store.allEntries().isEmpty())
            }
        },
    )

    private suspend fun runCatchingCancellation(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (failure: Throwable) {
        failure
    }
}
