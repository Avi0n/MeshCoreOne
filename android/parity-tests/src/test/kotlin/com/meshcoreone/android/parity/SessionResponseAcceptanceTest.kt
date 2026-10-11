// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/V115SessionMethodsTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/MeshCoreSessionGetChannelsTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/GetMessageSerializationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/V115GetMessageTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/AutoMessageFetchTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/GetMessageCoalescingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/GetMessageTimeoutTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/AutoContactRefreshTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/OtherParamsSerializationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/MeshCoreSessionCommandCorrelationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/RegionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/ProtocolBugFixTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.MessageResult
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionResponseAcceptanceTest {
    @OriginalCase("V115SessionMethodsTests::sendChannelData emits correct frame and awaits OK()")
    @OriginalCase("V115SessionMethodsTests::sendChannelData forwards pathLength and pathBytes to the builder()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope with name and key()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope clears with empty args()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope FloodScope overload \u2014 disabled clears()")
    @OriginalCase("V115SessionMethodsTests::setDefaultFloodScope FloodScope overload \u2014 channelName derives key()")
    @Test fun originalCommandsWaitForDelayedAcknowledgement() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            delayedOk(transport, "3e01ffffff010203") {
                session.sendChannelData(1u, 0xffffu, hex("010203"))
            }
            delayedOk(transport, "3e0003112233ffff42") {
                session.sendChannelData(0u, 0xffffu, hex("42"), 3u, hex("112233"))
            }
            delayedOk(transport, "3f4575726f7065" + "00".repeat(25) + "5a".repeat(16)) {
                session.setDefaultFloodScope("Europe", hex("5a".repeat(16)))
            }
            delayedOk(transport, "3f") { session.setDefaultFloodScope("", Bytes.EMPTY) }
            delayedOk(transport, "3f") { session.setDefaultFloodScope("ignored", FloodScope.Disabled) }
            delayedOk(transport, "3f707562" + "00".repeat(28) + "efa1f375d76194fa51a3556a97e641e6") {
                session.setDefaultFloodScope("pub", FloodScope.ChannelName("public"))
            }
            assertEquals("Startup plus exactly six acknowledged writes", 7, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("V115SessionMethodsTests::sendChannelData throws deviceError on error response()")
    @OriginalCase("V115SessionMethodsTests::getDefaultFloodScope propagates device errors()")
    @Test fun originalDeviceErrorsRemainTypedAfterDelayedResponse() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            delayedError(transport, "3e00ff010042", 2u) {
                session.sendChannelData(0u, 1u, hex("42"))
            }
            delayedError(transport, "40", 1u) { session.getDefaultFloodScope() }
            assertEquals("Startup plus exactly two failed writes", 3, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @Test fun noResponseRetainsSourceTenSecondTimeout() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            val call = async {
                try {
                    session.sendChannelData(1u, 0xffffu, hex("010203"))
                    error("A missing acknowledgment cannot become success")
                } catch (failure: MeshCoreException.Timeout) {
                    failure
                }
            }
            runCurrent()
            assertEquals(hex("3e01ffffff010203"), transport.sentData.last())
            advanceTimeBy(9_999)
            runCurrent()
            assertFalse(call.isCompleted)
            advanceTimeBy(1)
            runCurrent()
            assertTrue(call.await() is MeshCoreException.Timeout)
            assertEquals(2, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionGetChannelsTests::no more than the window is outstanding before the first response, then refills()")
    @Test fun originalWindowHoldsForFiftyMillisecondsAndRefillsWithoutMissingSlots() = runTest {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val configuration = sourcePipelineConfiguration(window = 2)
        val session = startSourceSession(transport, configuration, sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { session.getChannels(listOf(0u, 1u, 2u, 3u)) }
            runCurrent()
            assertEquals(listOf(hex("1f00"), hex("1f01")), transport.sentData.drop(1))
            advanceTimeBy(50)
            runCurrent()
            assertEquals("The original fifty-millisecond window must not issue a third read", 3, transport.sentData.size)
            assertFalse(call.isCompleted)
            transport.simulateReceive(sourceChannel(0, "zero"))
            runCurrent()
            assertEquals(4, transport.sentData.size)
            assertEquals(hex("1f02"), transport.sentData.last())
            transport.simulateReceive(sourceChannel(1, "one"))
            runCurrent()
            assertEquals(5, transport.sentData.size)
            assertEquals(hex("1f03"), transport.sentData.last())
            transport.simulateReceive(sourceChannel(2, "two"))
            transport.simulateReceive(sourceChannel(3, "three"))
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            val result = call.await()
            assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), result.received.map { it.index })
            assertTrue(result.missing.isEmpty())
            assertEquals(5, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionGetChannelsTests::a cancelled getChannels does not leak a late channelInfo into a same-type successor()")
    @Test fun originalCancelledPipelineKeepsTheSuccessorsIndexAndFreshName() = runTest {
        val transport = MockTransport()
        transport.setSupportsWriteWithoutResponse(true)
        val configuration = sourcePipelineConfiguration(idleTimeout = 0.1)
        val session = startSourceSession(transport, configuration, sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val pipeline = backgroundScope.async { session.getChannels(listOf(0u, 1u)) }
            runCurrent()
            assertEquals(3, transport.sentData.size)
            pipeline.cancel()
            runCurrent()
            assertTrue(pipeline.isCancelled)
            transport.simulateReceive(sourceChannel(0, "late", 0xAA))
            runCurrent()
            val successor = backgroundScope.async { session.getChannel(0u) }
            runCurrent()
            assertEquals("Successor must wait for the old exchange slot", 3, transport.sentData.size)
            advanceTimeBy(200)
            runCurrent()
            assertEquals(4, transport.sentData.size)
            assertEquals(hex("1f00"), transport.sentData.last())
            assertFalse("The late response cannot complete the successor", successor.isCompleted)
            transport.simulateReceive(sourceChannel(0, "fresh", 0xBB))
            runCurrent()
            val result = successor.await()
            assertEquals(0u.toUByte(), result.index)
            assertEquals("fresh", result.name)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionGetChannelsTests::each requested index lands in its own slot and unrequested indexes are ignored()")
    @OriginalCase("MeshCoreSessionGetChannelsTests::dropped writes surface as missing indexes after the idle timeout()")
    @OriginalCase("MeshCoreSessionGetChannelsTests::falls back to serial reads when the transport lacks write-without-response()")
    @Test fun originalChannelOrderingMissingAndSerialFamiliesKeepTheirExactInputs() = runTest {
        val outOfOrder = MockTransport()
        outOfOrder.setSupportsWriteWithoutResponse(true)
        val first = startSourceSession(outOfOrder, sourcePipelineConfiguration(),
            sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { first.getChannels(listOf(0u, 2u, 7u)) }
            runCurrent()
            assertEquals(listOf(hex("1f00"), hex("1f02"), hex("1f07")), outOfOrder.sentData.drop(1))
            outOfOrder.simulateReceive(sourceChannel(7, "seven", 0x77))
            outOfOrder.simulateReceive(sourceChannel(2, "two", 0x22))
            outOfOrder.simulateReceive(sourceChannel(5, "five", 0x55))
            outOfOrder.simulateReceive(sourceChannel(0, "zero"))
            runCurrent()
            advanceTimeBy(20)
            runCurrent()
            val result = call.await()
            assertTrue(result.missing.isEmpty())
            assertEquals(listOf<UByte>(0u, 2u, 7u), result.received.map { it.index })
            assertEquals(listOf("zero", "two", "seven"), result.received.map { it.name })
            assertFalse(result.received.any { it.index == 5u.toUByte() })
        } finally {
            first.stop()
        }

        val dropped = MockTransport()
        dropped.setSupportsWriteWithoutResponse(true)
        val second = startSourceSession(dropped, sourcePipelineConfiguration(idleTimeout = 0.1),
            sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { second.getChannels(listOf(0u, 1u, 2u, 3u)) }
            runCurrent()
            assertEquals(5, dropped.sentData.size)
            dropped.simulateReceive(sourceChannel(0, "zero"))
            dropped.simulateReceive(sourceChannel(1, "one"))
            dropped.simulateReceive(sourceChannel(3, "three"))
            runCurrent()
            advanceTimeBy(200)
            runCurrent()
            val result = call.await()
            assertEquals(listOf<UByte>(0u, 1u, 3u), result.received.map { it.index })
            assertEquals(listOf<UByte>(2u), result.missing)
        } finally {
            second.stop()
        }

        val serial = MockTransport()
        val third = startSourceSession(serial, sourcePipelineConfiguration(),
            sourceSelfInfo(915_000, 125_000, 7, "Test"))
        try {
            val call = backgroundScope.async { third.getChannels(listOf(0u, 1u)) }
            runCurrent()
            assertEquals(2, serial.sentData.size)
            assertEquals(hex("1f00"), serial.sentData.last())
            serial.simulateReceive(sourceChannel(0, "zero"))
            runCurrent()
            assertEquals(3, serial.sentData.size)
            assertEquals(hex("1f01"), serial.sentData.last())
            serial.simulateReceive(sourceChannel(1, "one"))
            runCurrent()
            val result = call.await()
            assertEquals(listOf<UByte>(0u, 1u), result.received.map { it.index })
            assertTrue(result.missing.isEmpty())
        } finally {
            third.stop()
        }
    }

    @OriginalCase("GetMessageSerializationTests::an unrelated error does not fail an in-flight getMessage()")
    @Test fun originalMessagePollWaitsFiftyMillisecondsBehindTheCommandAndItsError() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            assertEquals(hex("01032020202020204d43547374"), transport.sentData.single())
            val reset = backgroundScope.async {
                try {
                    session.factoryReset()
                    error("The reset must observe its own device error")
                } catch (failure: MeshCoreException.DeviceError) {
                    failure
                }
            }
            runCurrent()
            assertEquals(2, transport.sentData.size)
            assertEquals(hex("337265736574"), transport.sentData.last())
            val message = backgroundScope.async { session.getMessage() }
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertEquals("The original fifty-millisecond wait cannot send a poll early", 2, transport.sentData.size)
            assertFalse(reset.isCompleted)
            assertFalse(message.isCompleted)
            transport.simulateError(17u)
            runCurrent()
            assertEquals(17u.toUByte(), reset.await().code)
            assertEquals(3, transport.sentData.size)
            assertEquals(hex("0a"), transport.sentData.last())
            assertFalse("The reset's error cannot become the poll's result", message.isCompleted)
            transport.simulateReceive(hex("0a"))
            runCurrent()
            assertEquals(MessageResult.NoMoreMessages, message.await())
            assertEquals(3, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("V115GetMessageTests::getMessage yields channelDatagram()")
    @Test fun originalDatagramFrameReachesThePublicMessageResultAfterItsWrite() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport)
        try {
            assertEquals(hex("01032020202020204d43547374"), transport.sentData.single())
            val poll = backgroundScope.async { session.getMessage() }
            runCurrent()
            assertEquals(2, transport.sentData.size)
            assertEquals(hex("0a"), transport.sentData.last())
            assertFalse(poll.isCompleted)
            transport.simulateReceive(hex("1b00000002ffffff03deadbe"))
            runCurrent()
            val result = poll.await()
            assertTrue(result is MessageResult.ChannelDatagram)
            val datagram = (result as MessageResult.ChannelDatagram).datagram
            assertEquals(2u.toUByte(), datagram.channelIndex)
            assertEquals(0xffffu.toUShort(), datagram.dataType)
            assertEquals(hex("deadbe"), datagram.data)
            assertEquals(0xffu.toUByte(), datagram.pathLength)
            assertEquals(0.0, datagram.snr, 0.0)
            assertEquals(2, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("AutoMessageFetchTests::concurrent getMessage calls share one wire request()")
    @OriginalCase("AutoMessageFetchTests::auto-fetch coalesces repeated messagesWaiting notifications()")
    @OriginalCase("AutoMessageFetchTests::manual getMessage shares in-flight auto-fetch poll()")
    @Test fun originalAutomaticPollsRetainTheirSelfInfoAndClearStartupHistory() = runTest {
        for (scenario in listOf("concurrent", "waiting", "manual")) {
            val transport = MockTransport()
            val session = startSourceSession(transport,
                SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MeshCore-Tests"),
                sourceAutomaticSelfInfo())
            try {
                assertEquals(hex("01032020202020204d65736843"), transport.sentData.single())
                transport.clearSentData()
                if (scenario == "concurrent") {
                    val first = backgroundScope.async { session.getMessage(timeout = 10.0) }
                    val second = backgroundScope.async { session.getMessage(timeout = 10.0) }
                    runCurrent()
                    assertEquals(listOf(hex("0a")), transport.sentData)
                    assertFalse(first.isCompleted)
                    assertFalse(second.isCompleted)
                    transport.simulateReceive(hex("0a"))
                    runCurrent()
                    assertEquals(MessageResult.NoMoreMessages, first.await())
                    assertEquals(MessageResult.NoMoreMessages, second.await())
                    assertEquals(1, transport.sentData.size)
                } else {
                    session.startAutoMessageFetching()
                    repeat(if (scenario == "waiting") 3 else 1) { transport.simulateReceive(hex("83")) }
                    runCurrent()
                    assertEquals(listOf(hex("0a")), transport.sentData)
                    if (scenario == "manual") {
                        val manual = backgroundScope.async { session.getMessage(timeout = 10.0) }
                        advanceTimeBy(20)
                        runCurrent()
                        assertFalse(manual.isCompleted)
                        assertEquals(1, transport.sentData.size)
                        transport.simulateReceive(hex("0a"))
                        runCurrent()
                        assertEquals(MessageResult.NoMoreMessages, manual.await())
                        assertEquals(1, transport.sentData.size)
                    } else {
                        transport.simulateReceive(hex("0a"))
                        advanceTimeBy(50)
                        runCurrent()
                        assertTrue(transport.sentData.size in 1..2)
                        if (transport.sentData.size == 2) {
                            transport.simulateReceive(hex("0a"))
                            advanceTimeBy(20)
                            runCurrent()
                            assertEquals(2, transport.sentData.size)
                        }
                    }
                    session.stopAutoMessageFetching()
                    val stoppedWrites = transport.sentData.size
                    transport.simulateReceive(hex("83"))
                    advanceTimeBy(50)
                    runCurrent()
                    assertEquals(stoppedWrites, transport.sentData.size)
                }
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("GetMessageCoalescingTests::a coalesced caller shares the leader's frame and result()")
    @OriginalCase("GetMessageCoalescingTests::a coalesced caller is released when the leader times out()")
    @OriginalCase("GetMessageCoalescingTests::a coalesced caller is released when the leader fails with a device error()")
    @OriginalCase("GetMessageCoalescingTests::a cancelled coalesced caller does not hang()")
    @Test fun originalCoalescedOutcomesKeepFollowerWaitsAndCancellationBudget() = runTest {
        for (scenario in listOf("result", "timeout", "error", "cancelled")) {
            val transport = MockTransport()
            val session = startSourceSession(transport)
            try {
                val timeout = when (scenario) { "timeout" -> 0.15; "cancelled" -> 0.3; else -> null }
                val leader = backgroundScope.async<Result<MessageResult>> {
                    try {
                        Result.success(session.getMessage(timeout))
                    } catch (failure: MeshCoreException.Timeout) {
                        Result.failure(failure)
                    } catch (failure: MeshCoreException.DeviceError) {
                        Result.failure(failure)
                    }
                }
                runCurrent()
                val follower = backgroundScope.async<Result<MessageResult>> {
                    try {
                        Result.success(session.getMessage())
                    } catch (failure: MeshCoreException.Timeout) {
                        Result.failure(failure)
                    } catch (failure: MeshCoreException.DeviceError) {
                        Result.failure(failure)
                    }
                }
                advanceTimeBy(if (scenario == "result") 100 else 50)
                runCurrent()
                assertEquals(listOf(hex("01032020202020204d43547374"), hex("0a")), transport.sentData)
                assertFalse(leader.isCompleted)
                assertFalse(follower.isCompleted)
                when (scenario) {
                    "result" -> {
                        transport.simulateReceive(hex("0a"))
                        runCurrent()
                        assertEquals(MessageResult.NoMoreMessages, leader.await().getOrThrow())
                        assertEquals(MessageResult.NoMoreMessages, follower.await().getOrThrow())
                    }
                    "error" -> {
                        transport.simulateError(9u)
                        runCurrent()
                        for (result in listOf(leader.await(), follower.await())) {
                            val failure = result.exceptionOrNull()
                            assertTrue(failure is MeshCoreException.DeviceError)
                            assertEquals(9u.toUByte(), (failure as MeshCoreException.DeviceError).code)
                        }
                    }
                    "timeout" -> {
                        advanceTimeBy(99)
                        runCurrent()
                        assertFalse(leader.isCompleted)
                        assertFalse(follower.isCompleted)
                        advanceTimeBy(1)
                        runCurrent()
                        assertTrue(leader.await().exceptionOrNull() is MeshCoreException.Timeout)
                        assertTrue(follower.await().exceptionOrNull() is MeshCoreException.Timeout)
                    }
                    "cancelled" -> {
                        val cancellationStarted = System.nanoTime()
                        follower.cancel(CancellationException("source-coalesced-caller"))
                        runCurrent()
                        assertTrue(follower.isCompleted)
                        try {
                            follower.await()
                            fail("The cancelled follower cannot become a successful result")
                        } catch (_: CancellationException) {
                            assertTrue(System.nanoTime() - cancellationStarted < 2_000_000_000L)
                        }
                        assertFalse(leader.isCompleted)
                        advanceTimeBy(250)
                        runCurrent()
                        assertTrue(leader.await().exceptionOrNull() is MeshCoreException.Timeout)
                    }
                }
                assertEquals(2, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("GetMessageTimeoutTests::getMessage times out when no response arrives()")
    @OriginalCase("GetMessageTimeoutTests::getMessage timeout override can be shorter than the session default()")
    @OriginalCase("GetMessageTimeoutTests::getMessage timeout override can extend beyond the session default()")
    @Test fun originalTimeoutOverridesUseRealDeadlinesAndSourceInputs() = runBlocking {
        for (scenario in listOf("default", "shorter", "longer")) {
            val transport = MockTransport()
            transport.connect()
            val session = MeshCoreSession(transport,
                SessionConfiguration(defaultTimeout = if (scenario == "shorter") 2.0 else 0.02,
                    clientIdentifier = "MeshCore-Tests"))
            try {
                val started = System.nanoTime()
                try {
                    withTimeout(5_000) {
                        session.getMessage(when (scenario) { "shorter" -> 0.02; "longer" -> 0.12; else -> null })
                    }
                    fail("The missing response cannot become a successful poll")
                } catch (_: MeshCoreException.Timeout) {
                    val elapsed = System.nanoTime() - started
                    if (scenario == "shorter") assertTrue("Original under-one-second bound", elapsed < 1_000_000_000L)
                    if (scenario == "longer") assertTrue("Original at-least-eighty-millisecond bound", elapsed >= 80_000_000L)
                    assertEquals(listOf(hex("0a")), transport.sentData)
                }
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("AutoContactRefreshTests::auto-refresh coalesces bursty contact invalidations()")
    @Test fun originalContactInvalidationsRetainSourceSelfInfoHistoryAndFiftyMillisecondObservation() = runTest {
        val transport = MockTransport()
        val session = startSourceSession(transport,
            SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MeshCore-Tests"),
            sourceAutomaticSelfInfo())
        try {
            assertEquals(hex("01032020202020204d65736843"), transport.sentData.single())
            transport.clearSentData()
            session.setAutoUpdateContacts(true)
            repeat(3) { transport.simulateReceive(hex("80" + "22".repeat(32))) }
            runCurrent()
            assertEquals(listOf(hex("04")), transport.sentData)
            transport.simulateReceive(hex("0200000000"))
            transport.simulateReceive(hex("0401000000"))
            advanceTimeBy(50)
            runCurrent()
            assertTrue(transport.sentData.size in 1..2)
            if (transport.sentData.size == 2) {
                assertEquals(hex("0401000000"), transport.sentData.last())
                transport.simulateReceive(hex("0200000000"))
                transport.simulateReceive(hex("0402000000"))
                advanceTimeBy(50)
                runCurrent()
            }
            assertTrue(transport.sentData.size <= 2)
            assertFalse(session.isContactsDirty)
            assertTrue(session.cachedContacts.isEmpty())
        } finally {
            session.stop()
        }
    }

    @OriginalCase("OtherParamsSerializationTests::concurrent granular setters do not revert each other()")
    @Test fun originalConcurrentSettingsWaitForEachFreshSelfInfoBeforeTheNextWrite() = runTest {
        for (manualFirst in listOf(true, false)) {
            val transport = MockTransport()
            val session = startSourceSession(transport)
            try {
                val first = backgroundScope.async {
                    if (manualFirst) session.setManualAddContacts(true) else session.setMultiAcks(7u)
                }
                val second = backgroundScope.async {
                    if (manualFirst) session.setMultiAcks(7u) else session.setManualAddContacts(true)
                }
                runCurrent()
                val startup = hex("01032020202020204d43547374")
                val initialWrite = hex(if (manualFirst) "2601000000" else "2600000007")
                assertEquals(listOf(startup, initialWrite), transport.sentData)
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                assertEquals(false, session.currentSelfInfo?.manualAddContacts)
                assertEquals(0u.toUByte(), session.currentSelfInfo?.multiAcks)
                transport.simulateOK()
                runCurrent()
                advanceTimeBy(50)
                runCurrent()
                assertEquals(listOf(startup, initialWrite, startup), transport.sentData)
                assertFalse(first.isCompleted)
                assertFalse(second.isCompleted)
                assertEquals(false, session.currentSelfInfo?.manualAddContacts)
                assertEquals(0u.toUByte(), session.currentSelfInfo?.multiAcks)
                transport.simulateReceive(hex("05011616" + "01".repeat(32) + "00".repeat(8) +
                    (if (manualFirst) "00000001" else "07000000") + "95440d0090d003000b05"))
                runCurrent()
                first.await()
                assertEquals(listOf(startup, initialWrite, startup, hex("2601000007")), transport.sentData)
                assertFalse(second.isCompleted)
                transport.simulateOK()
                runCurrent()
                advanceTimeBy(50)
                runCurrent()
                assertEquals(listOf(startup, initialWrite, startup, hex("2601000007"), startup), transport.sentData)
                assertFalse(second.isCompleted)
                transport.simulateReceive(hex("05011616" + "01".repeat(32) + "00".repeat(8) +
                    "0700000195440d0090d003000b05"))
                runCurrent()
                second.await()
                assertEquals(true, session.currentSelfInfo?.manualAddContacts)
                assertEquals(7u.toUByte(), session.currentSelfInfo?.multiAcks)
                assertEquals(5, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::session start ignores unrelated errors until selfInfo arrives()")
    @Test fun originalStartupIgnoresErrorNinetyNineBeforeItsExactSelfInfo() = runTest {
        val transport = MockTransport()
        val session = MeshCoreSession(transport,
            SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"),
            publicSessionClock(), backgroundScope.coroutineContext)
        try {
            val starting = backgroundScope.async { session.start() }
            runCurrent()
            assertEquals(listOf(hex("01032020202020204d43547374")), transport.sentData)
            assertFalse(starting.isCompleted)
            transport.simulateError(99u)
            runCurrent()
            assertFalse(starting.isCompleted)
            assertNull(session.currentSelfInfo)
            val selfInfo = sourceSelfInfo(915_000, 125_000, 7, "Test")
            assertEquals(62, selfInfo.size)
            transport.simulateReceive(selfInfo)
            runCurrent()
            starting.await()
            assertEquals("Test", session.currentSelfInfo?.name)
            assertEquals(1, transport.sentData.size)
        } finally {
            session.stop()
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::simple commands serialize concurrent OK/ERROR waits()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::simple commands ignore OK responses with payloads()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::simple commands still fail on device errors()")
    @Test fun originalSimpleCorrelationKeepsFiftyMillisecondWaitPayloadSevenAndErrorFortyTwo() = runTest {
        for (scenario in listOf("serialized", "payload", "device-error")) {
            val transport = MockTransport()
            val session = startSourceSession(transport,
                SessionConfiguration(defaultTimeout = if (scenario == "payload") 0.2 else 10.0,
                    clientIdentifier = "MCTst"),
                sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                if (scenario == "serialized") {
                    val first = backgroundScope.async { session.factoryReset() }
                    val second = backgroundScope.async { session.sendAdvertisement(true) }
                    runCurrent()
                    advanceTimeBy(50)
                    runCurrent()
                    assertEquals(listOf(hex("01032020202020204d43547374"), hex("337265736574")), transport.sentData)
                    assertFalse(first.isCompleted)
                    assertFalse(second.isCompleted)
                    transport.simulateOK()
                    runCurrent()
                    first.await()
                    assertEquals(3, transport.sentData.size)
                    assertEquals(hex("0701"), transport.sentData.last())
                    assertFalse(second.isCompleted)
                    transport.simulateOK()
                    runCurrent()
                    second.await()
                    assertEquals(3, transport.sentData.size)
                } else if (scenario == "payload") {
                    val started = testScheduler.currentTime
                    val reset = backgroundScope.async {
                        try {
                            session.factoryReset()
                            fail("An OK payload cannot satisfy a simple command")
                        } catch (failure: MeshCoreException.Timeout) {
                            failure
                        }
                    }
                    runCurrent()
                    assertEquals(hex("337265736574"), transport.sentData.last())
                    transport.simulateOK(7u)
                    advanceTimeBy(199)
                    runCurrent()
                    assertFalse(reset.isCompleted)
                    assertEquals(2, transport.sentData.size)
                    advanceTimeBy(1)
                    runCurrent()
                    assertTrue(reset.await() is MeshCoreException.Timeout)
                    assertEquals(200L, testScheduler.currentTime - started)
                    assertEquals(2, transport.sentData.size)
                } else {
                    delayedError(transport, "3a1e02", 42u) {
                        session.setAutoAddConfig(AutoAddConfig(0x1eu, 2u))
                    }
                    assertEquals(2, transport.sentData.size)
                }
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getBattery ignores unrelated errors while waiting for a battery response()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getSelfTelemetry ignores telemetry for other nodes()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getChannel ignores responses for other channel indexes()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getContact ignores responses for other public keys()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::exportContact ignores contact URIs for other public keys()")
    @Test fun originalResponsePredicatesRetainTheirKeysIndexesAndLiteralPackets() = runTest {
        for (scenario in listOf("battery", "telemetry", "channel", "contact", "uri")) {
            val transport = MockTransport()
            val session = startSourceSession(transport,
                selfInfo = sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                when (scenario) {
                    "battery" -> {
                        val response = backgroundScope.async { session.getBattery() }
                        runCurrent()
                        assertEquals(hex("14"), transport.sentData.last())
                        transport.simulateError(10u)
                        runCurrent()
                        assertFalse(response.isCompleted)
                        transport.simulateReceive(hex("0cb20f"))
                        runCurrent()
                        assertEquals(4018L, response.await().level)
                    }
                    "telemetry" -> {
                        val response = backgroundScope.async { session.getSelfTelemetry() }
                        runCurrent()
                        assertEquals(hex("27000000"), transport.sentData.last())
                        transport.simulateReceive(hex("8b00aabbccddeeff016700fa"))
                        runCurrent()
                        assertFalse(response.isCompleted)
                        transport.simulateReceive(hex("8b00" + "01".repeat(6) + "016700f0"))
                        runCurrent()
                        assertEquals(hex("01".repeat(6)), response.await().publicKeyPrefix)
                        assertEquals(hex("016700f0"), response.await().rawData)
                    }
                    "channel" -> {
                        val response = backgroundScope.async { session.getChannel(3u) }
                        runCurrent()
                        assertEquals(hex("1f03"), transport.sentData.last())
                        transport.simulateReceive(sourceChannel(9, "Wrong", 0xaa))
                        runCurrent()
                        assertFalse(response.isCompleted)
                        transport.simulateReceive(sourceChannel(3, "Right", 0xbb))
                        runCurrent()
                        assertEquals(3u.toUByte(), response.await().index)
                        assertEquals("Right", response.await().name)
                    }
                    "contact" -> {
                        val key = hex("11".repeat(32))
                        val response = backgroundScope.async { session.getContact(key) }
                        runCurrent()
                        assertEquals(hex("1e") + key, transport.sentData.last())
                        transport.simulateReceive(sourceCorrelationContact(0x22, "Wrong"))
                        runCurrent()
                        assertFalse(response.isCompleted)
                        transport.simulateReceive(sourceCorrelationContact(0x11, "Right"))
                        runCurrent()
                        val contact = response.await()
                        assertNotNull(contact)
                        assertEquals(key, contact!!.publicKey)
                        assertEquals("Right", contact.advertisedName)
                    }
                    "uri" -> {
                        val key = hex("11".repeat(32))
                        val response = backgroundScope.async { session.exportContact(key) }
                        runCurrent()
                        assertEquals(hex("11") + key, transport.sentData.last())
                        transport.simulateReceive(hex("0b" + "22".repeat(32) + "cd".repeat(8)))
                        runCurrent()
                        assertFalse(response.isCompleted)
                        transport.simulateReceive(hex("0b" + "11".repeat(32) + "cd".repeat(8)))
                        runCurrent()
                        val uri = response.await()
                        assertTrue(uri.contains("11".repeat(32)))
                        assertFalse(uri.contains("22".repeat(32)))
                    }
                }
                assertEquals(2, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getContacts succeeds when slow stream keeps making progress()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getContactsReportingTotal surfaces the contactsStart total, not the received count()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getContactsReportingTotal returns a nil total when contactsStart never arrives()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::getContacts times out after inactivity before contactsEnd()")
    @Test fun originalContactStreamsKeepCadenceDeclaredTotalsAndInactivityInputs() = runTest {
        for (scenario in listOf("slow", "truncated", "no-header", "inactive")) {
            val transport = MockTransport()
            val session = startSourceSession(transport,
                SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst",
                    contactStreamInactivityTimeout = if (scenario == "inactive") 0.08 else 1.0,
                    contactStreamHardTimeout = if (scenario == "inactive") 1.0 else 10.0),
                sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                val started = testScheduler.currentTime
                if (scenario == "inactive") {
                    val response = backgroundScope.async {
                        try {
                            session.getContacts()
                            fail("A stalled contact stream cannot become an empty successful snapshot")
                        } catch (failure: MeshCoreException.Timeout) {
                            failure
                        }
                    }
                    runCurrent()
                    assertEquals(hex("04"), transport.sentData.last())
                    transport.simulateReceive(hex("0201000000"))
                    runCurrent()
                    advanceTimeBy(79)
                    runCurrent()
                    assertFalse(response.isCompleted)
                    advanceTimeBy(61)
                    runCurrent()
                    assertEquals(140L, testScheduler.currentTime - started)
                    assertTrue(response.await() is MeshCoreException.Timeout)
                    assertTrue(session.isContactsDirty)
                } else {
                    val response = backgroundScope.async {
                        if (scenario == "slow") {
                            session.getContacts() to null
                        } else {
                            val result = session.getContactsReportingTotal()
                            result.contacts to result.reportedTotal
                        }
                    }
                    runCurrent()
                    assertEquals(hex("04"), transport.sentData.last())
                    if (scenario != "no-header") transport.simulateReceive(hex("0203000000"))
                    runCurrent()
                    repeat(if (scenario == "slow") 3 else 2) { index ->
                        if (scenario == "slow") advanceTimeBy(70)
                        val contact = sourceCorrelationContact(index + 1, "Node $index")
                        assertEquals(148, contact.size)
                        transport.simulateReceive(contact)
                        runCurrent()
                        assertFalse(response.isCompleted)
                    }
                    if (scenario == "slow") advanceTimeBy(70)
                    transport.simulateReceive(hex("0480009265"))
                    runCurrent()
                    val result = response.await()
                    assertEquals(if (scenario == "slow") 3 else 2, result.first.size)
                    assertEquals(if (scenario == "truncated") 3L else null, result.second)
                    assertEquals(if (scenario == "slow") listOf("Node 0", "Node 1", "Node 2") else
                        listOf("Node 0", "Node 1"), result.first.map { it.advertisedName })
                    if (scenario == "slow") assertEquals(280L, testScheduler.currentTime - started)
                    assertEquals(scenario != "slow", session.isContactsDirty)
                }
                assertEquals(2, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::importPrivateKey ignores OK responses with payloads()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::importPrivateKey refreshes cached self info after OK()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::importPrivateKey rejects a key that is not the expanded private-key length()")
    @Test fun originalPrivateKeyImportRetainsExpandedLengthPayloadTimeoutAndFreshIdentity() = runTest {
        val invalidTransport = MockTransport()
        val invalidSession = MeshCoreSession(invalidTransport,
            SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"),
            publicSessionClock(), backgroundScope.coroutineContext)
        try {
            try {
                invalidSession.importPrivateKey(hex("33".repeat(32)))
                fail("A public-key-length input cannot be imported as an expanded private key")
            } catch (failure: MeshCoreException.InvalidInput) {
                assertTrue(invalidTransport.sentData.isEmpty())
                assertFalse(invalidTransport.isConnected())
            }
        } finally {
            invalidSession.stop()
        }
        for (payloadOnly in listOf(true, false)) {
            val transport = MockTransport()
            val session = startSourceSession(transport,
                SessionConfiguration(defaultTimeout = if (payloadOnly) 0.2 else 10.0, clientIdentifier = "MCTst"),
                sourceSelfInfo(915_000, 125_000, 7, if (payloadOnly) "Test" else "Temp"))
            try {
                assertEquals(hex("01".repeat(32)), session.currentSelfInfo?.publicKey)
                val started = testScheduler.currentTime
                val importing = backgroundScope.async {
                    try {
                        session.importPrivateKey(hex("33".repeat(64)))
                        null
                    } catch (failure: MeshCoreException.Timeout) {
                        failure
                    }
                }
                runCurrent()
                assertEquals(listOf(hex("01032020202020204d43547374"), hex("18" + "33".repeat(64))),
                    transport.sentData)
                assertFalse(importing.isCompleted)
                if (payloadOnly) {
                    transport.simulateOK(7u)
                    advanceTimeBy(199)
                    runCurrent()
                    assertFalse(importing.isCompleted)
                    assertEquals(2, transport.sentData.size)
                    advanceTimeBy(1)
                    runCurrent()
                    assertTrue(importing.await() is MeshCoreException.Timeout)
                    assertEquals(200L, testScheduler.currentTime - started)
                    assertEquals(2, transport.sentData.size)
                } else {
                    transport.simulateOK()
                    runCurrent()
                    assertEquals(3, transport.sentData.size)
                    assertEquals(hex("01032020202020204d43547374"), transport.sentData.last())
                    assertFalse(importing.isCompleted)
                    assertEquals("Temp", session.currentSelfInfo?.name)
                    assertEquals(hex("01".repeat(32)), session.currentSelfInfo?.publicKey)
                    val restored = sourceSelfInfo(915_000, 125_000, 7, "Restored").toByteArray()
                    restored.fill(0x44.toByte(), 4, 36)
                    assertEquals(66, restored.size)
                    transport.simulateReceive(Bytes(restored))
                    runCurrent()
                    assertNull(importing.await())
                    assertEquals(hex("44".repeat(32)), session.currentSelfInfo?.publicKey)
                    assertEquals("Restored", session.currentSelfInfo?.name)
                    assertEquals(3, transport.sentData.size)
                }
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::exportPrivateKey throws featureDisabled on disabled response()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::disabled responses do not break unrelated requests()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::exportPrivateKey fails fast on device error()")
    @Test fun originalDisabledAndPrivateKeyErrorEightKeepTheirRequestSpecificOutcomes() = runTest {
        for (scenario in listOf("disabled-export", "disabled-battery", "error-export")) {
            val transport = MockTransport()
            val session = startSourceSession(transport, selfInfo = sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                if (scenario == "disabled-battery") {
                    val battery = backgroundScope.async { session.getBattery() }
                    runCurrent()
                    assertEquals(hex("14"), transport.sentData.last())
                    transport.simulateReceive(hex("0f"))
                    runCurrent()
                    assertFalse(battery.isCompleted)
                    transport.simulateReceive(hex("0cb20f"))
                    runCurrent()
                    assertEquals(4018L, battery.await().level)
                } else {
                    val exporting = backgroundScope.async {
                        try {
                            session.exportPrivateKey()
                            fail("Private-key rejection cannot become a successful key")
                        } catch (failure: MeshCoreException.FeatureDisabled) {
                            assertEquals("disabled-export", scenario)
                            failure
                        } catch (failure: MeshCoreException.DeviceError) {
                            assertEquals("error-export", scenario)
                            failure
                        }
                    }
                    runCurrent()
                    assertEquals(hex("17"), transport.sentData.last())
                    assertFalse(exporting.isCompleted)
                    if (scenario == "disabled-export") transport.simulateReceive(hex("0f"))
                    else transport.simulateError(8u)
                    runCurrent()
                    val failure = exporting.await()
                    if (scenario == "disabled-export") assertTrue(failure is MeshCoreException.FeatureDisabled)
                    else {
                        assertTrue(failure is MeshCoreException.DeviceError)
                        assertEquals(8u.toUByte(), (failure as MeshCoreException.DeviceError).code)
                    }
                }
                assertEquals(2, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::requestStatus fails fast on device error before messageSent()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::requestTelemetry fails fast on device error before messageSent()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::requestStatus uses dedicated status command and room layout for typed room targets()")
    @Test fun originalBinaryPreTagErrorsAndDedicatedRoomStatusUseExactSourceFrames() = runTest {
        val target = hex("31".repeat(32))
        for (scenario in listOf("status-error", "telemetry-error", "room")) {
            val transport = MockTransport()
            val session = startSourceSession(transport, selfInfo = sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                if (scenario == "room") {
                    val response = backgroundScope.async { session.requestStatus(target, ContactType.ROOM) }
                    runCurrent()
                    assertEquals(hex("1b") + target, transport.sentData.last())
                    assertFalse(response.isCompleted)
                    transport.simulateReceive(hex("0600aabbccdd88130000"))
                    runCurrent()
                    assertFalse(response.isCompleted)
                    val statusFrame = sourceCorrelationStatus(1000, 17, 9)
                    assertEquals(60, statusFrame.size)
                    transport.simulateReceive(statusFrame)
                    runCurrent()
                    val status = response.await()
                    assertEquals(1000L, status.battery)
                    assertEquals(17u.toUShort(), status.roomServerPostedCount)
                    assertEquals(9u.toUShort(), status.roomServerPostPushCount)
                    assertEquals(0u, status.rxAirtime)
                } else {
                    val response = backgroundScope.async {
                        try {
                            if (scenario == "status-error") session.requestStatus(target) else session.requestTelemetry(target)
                            error("A pre-messageSent binary rejection cannot become success")
                        } catch (failure: MeshCoreException.DeviceError) {
                            failure
                        }
                    }
                    runCurrent()
                    assertEquals(if (scenario == "status-error") hex("1b") + target else hex("27000000") + target,
                        transport.sentData.last())
                    assertFalse(response.isCompleted)
                    transport.simulateError(if (scenario == "status-error") 10u else 11u)
                    runCurrent()
                    assertEquals((if (scenario == "status-error") 10u else 11u).toUByte(), response.await().code)
                }
                assertEquals(2, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::requestStatus retransmits until a matching response arrives()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::binary response matches only the latest retransmit tag()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::requestStatus times out after the overall budget without a reply()")
    @Test fun originalBinaryCadenceLatestTagAndOverallBudgetKeepSourceIntervals() = runTest {
        val target = hex("31".repeat(32))
        for (scenario in listOf("routed", "latest-tag", "overall")) {
            val transport = MockTransport()
            val session = startSourceSession(transport,
                SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst",
                    binaryRequestOverallTimeout = if (scenario == "overall") 0.5 else 2.0,
                    binaryRequestRetransmitInterval = 0.05),
                sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                val started = testScheduler.currentTime
                if (scenario == "overall") {
                    val response = backgroundScope.async {
                        try {
                            session.requestStatus(target)
                            fail("An unanswered binary request cannot become successful status")
                        } catch (failure: MeshCoreException.Timeout) {
                            failure
                        }
                    }
                    runCurrent()
                    assertEquals(hex("1b") + target, transport.sentData.last())
                    transport.simulateReceive(hex("06000102030432000000"))
                    runCurrent()
                    advanceTimeBy(499)
                    runCurrent()
                    assertFalse(response.isCompleted)
                    assertTrue(transport.sentData.drop(1).size >= 2)
                    assertTrue(transport.sentData.drop(1).all { it == hex("1b") + target })
                    advanceTimeBy(1)
                    runCurrent()
                    assertTrue(response.await() is MeshCoreException.Timeout)
                    assertEquals(500L, testScheduler.currentTime - started)
                } else {
                    val status = if (scenario == "routed") backgroundScope.async { session.requestStatus(target) } else null
                    val telemetry = if (scenario == "latest-tag") backgroundScope.async { session.requestTelemetry(target) } else null
                    runCurrent()
                    val request = if (scenario == "routed") hex("1b") + target else hex("27000000") + target
                    assertEquals(request, transport.sentData.last())
                    transport.simulateReceive(hex("060011223344f4010000"))
                    runCurrent()
                    advanceTimeBy(999)
                    runCurrent()
                    assertEquals(2, transport.sentData.size)
                    advanceTimeBy(1)
                    runCurrent()
                    assertEquals(listOf(hex("01032020202020204d43547374"), request, request), transport.sentData)
                    transport.simulateReceive(hex("060055667788f4010000"))
                    runCurrent()
                    if (scenario == "routed") {
                        transport.simulateReceive(sourceCorrelationStatus(2200))
                        runCurrent()
                        assertEquals(2200L, checkNotNull(status).await().battery)
                        assertTrue(transport.sentData.drop(1).size >= 2)
                    } else {
                        val response = checkNotNull(telemetry)
                        transport.simulateReceive(hex("8c0011223344"))
                        advanceTimeBy(80)
                        runCurrent()
                        assertFalse(response.isCancelled)
                        assertFalse(response.isCompleted)
                        assertEquals(1080L, testScheduler.currentTime - started)
                        transport.simulateReceive(hex("8c0055667788"))
                        runCurrent()
                        assertTrue(response.await().dataPoints.isEmpty())
                    }
                    assertEquals(3, transport.sentData.size)
                }
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::sendMessage fails fast on device error()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::sendKeepAlive fails fast on device error()")
    @Test fun originalTextAndKeepAliveErrorsRetainHelloKeysAndTypedCodes() = runTest {
        for (message in listOf(true, false)) {
            val transport = MockTransport()
            val session = startSourceSession(transport, selfInfo = sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                val response = backgroundScope.async {
                    try {
                        if (message) session.sendMessage(hex("11".repeat(32)), "hello", Instant.ofEpochSecond(1_704_067_200))
                        else session.sendKeepAlive(hex("22".repeat(32)), 0u)
                        error("Device rejection cannot return a successful messageSent")
                    } catch (failure: MeshCoreException.DeviceError) {
                        failure
                    }
                }
                runCurrent()
                assertEquals(if (message) hex("02000080009265" + "11".repeat(6) + "68656c6c6f")
                    else hex("32" + "22".repeat(32) + "0200000000"), transport.sentData.last())
                assertFalse(response.isCompleted)
                transport.simulateError(if (message) 5u else 3u)
                runCurrent()
                assertEquals((if (message) 5u else 3u).toUByte(), response.await().code)
                assertEquals(2, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::binary request serializes behind a concurrent text command()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::binary request errors release the serializer for following requests()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::concurrent unicast send and binary request do not share one messageSent()")
    @Test fun originalCrossRequestOwnershipKeepsFiftyMillisecondsAndDistinctErrorsAndTags() = runTest {
        for (scenario in listOf("text-first", "binary-first", "unicast")) {
            val transport = MockTransport()
            val session = startSourceSession(transport,
                SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst",
                    binaryRequestOverallTimeout = if (scenario == "binary-first") 2.0 else 40.0,
                    binaryRequestRetransmitInterval = if (scenario == "binary-first") null else 1.0),
                sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                val target = hex("31".repeat(32))
                if (scenario == "unicast") {
                    val status = backgroundScope.async { session.requestStatus(target, ContactType.ROOM) }
                    runCurrent()
                    assertEquals(hex("1b") + target, transport.sentData.last())
                    val message = backgroundScope.async {
                        session.sendMessage(hex("11".repeat(32)), "hi", Instant.ofEpochSecond(1_704_067_200))
                    }
                    advanceTimeBy(50)
                    runCurrent()
                    assertEquals(2, transport.sentData.size)
                    assertFalse(status.isCompleted)
                    assertFalse(message.isCompleted)
                    transport.simulateReceive(hex("0600aabbccdd88130000"))
                    transport.simulateReceive(sourceCorrelationStatus(1234, 5, 2))
                    runCurrent()
                    assertEquals(1234L, status.await().battery)
                    assertEquals(3, transport.sentData.size)
                    assertEquals(hex("02000080009265" + "11".repeat(6) + "6869"), transport.sentData.last())
                    assertFalse(message.isCompleted)
                    transport.simulateReceive(hex("06001122334488130000"))
                    runCurrent()
                    assertEquals(hex("11223344"), message.await().expectedAck)
                } else {
                    val first = backgroundScope.async {
                        try {
                            if (scenario == "text-first") session.sendKeepAlive(hex("22".repeat(32)), 0u)
                            else session.requestStatus(target)
                            error("First request must preserve its own device error")
                        } catch (failure: MeshCoreException.DeviceError) {
                            failure
                        }
                    }
                    runCurrent()
                    assertEquals(if (scenario == "text-first") hex("32" + "22".repeat(32) + "0200000000")
                        else hex("1b") + target, transport.sentData.last())
                    val second = backgroundScope.async {
                        try {
                            if (scenario == "text-first") session.requestStatus(target)
                            else session.requestTelemetry(hex("42".repeat(32)))
                            error("Second request must preserve its own device error")
                        } catch (failure: MeshCoreException.DeviceError) {
                            failure
                        }
                    }
                    advanceTimeBy(50)
                    runCurrent()
                    assertEquals(2, transport.sentData.size)
                    assertFalse(first.isCompleted)
                    assertFalse(second.isCompleted)
                    transport.simulateError(if (scenario == "text-first") 42u else 12u)
                    runCurrent()
                    assertEquals((if (scenario == "text-first") 42u else 12u).toUByte(), first.await().code)
                    assertEquals(3, transport.sentData.size)
                    assertEquals(if (scenario == "text-first") hex("1b") + target
                        else hex("27000000" + "42".repeat(32)), transport.sentData.last())
                    assertFalse(second.isCompleted)
                    transport.simulateError(if (scenario == "text-first") 43u else 13u)
                    runCurrent()
                    assertEquals((if (scenario == "text-first") 43u else 13u).toUByte(), second.await().code)
                }
                assertEquals(3, transport.sentData.size)
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("MeshCoreSessionCommandCorrelationTests::a response orphaned by a cancelled command is not delivered to the next command()")
    @OriginalCase("MeshCoreSessionCommandCorrelationTests::a command cancelled while waiting on the serializer never writes()")
    @Test fun originalCancelledOwnershipDrainsBatteryAndNeverWritesParkedAdvertisement() = runTest {
        for (orphan in listOf(true, false)) {
            val transport = MockTransport()
            val session = startSourceSession(transport, selfInfo = sourceSelfInfo(915_000, 125_000, 7, "Test"))
            try {
                if (orphan) {
                    val first = backgroundScope.async { session.getBattery() }
                    runCurrent()
                    assertEquals(hex("14"), transport.sentData.last())
                    first.cancel(CancellationException("source-first-battery-cancelled"))
                    runCurrent()
                    try {
                        first.await()
                        fail("Cancelled caller cannot receive a successful orphaned battery")
                    } catch (failure: CancellationException) {
                        assertEquals("source-first-battery-cancelled", failure.message)
                    }
                    val second = backgroundScope.async { session.getBattery() }
                    advanceTimeBy(50)
                    runCurrent()
                    assertEquals(2, transport.sentData.size)
                    assertFalse(second.isCompleted)
                    transport.simulateReceive(hex("0c5704"))
                    runCurrent()
                    assertEquals(3, transport.sentData.size)
                    assertEquals(hex("14"), transport.sentData.last())
                    assertFalse(second.isCompleted)
                    transport.simulateReceive(hex("0cae08"))
                    runCurrent()
                    assertEquals(2222L, second.await().level)
                    assertEquals(3, transport.sentData.size)
                } else {
                    val first = backgroundScope.async { session.factoryReset() }
                    runCurrent()
                    assertEquals(hex("337265736574"), transport.sentData.last())
                    val second = backgroundScope.async { session.sendAdvertisement(true) }
                    advanceTimeBy(50)
                    runCurrent()
                    assertEquals(2, transport.sentData.size)
                    assertFalse(first.isCompleted)
                    assertFalse(second.isCompleted)
                    second.cancel(CancellationException("source-parked-advertisement-cancelled"))
                    transport.simulateOK()
                    runCurrent()
                    first.await()
                    try {
                        second.await()
                        fail("Cancellation before admission cannot become a sent advertisement")
                    } catch (failure: CancellationException) {
                        assertEquals("source-parked-advertisement-cancelled", failure.message)
                    }
                    assertEquals(listOf(hex("01032020202020204d43547374"), hex("337265736574")),
                        transport.sentData)
                }
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("RequestRegionsIntegrationTests::full two-phase flow returns parsed regions()")
    @OriginalCase("RequestRegionsIntegrationTests::timeout when no binaryResponse arrives()")
    @OriginalCase("RequestRegionsIntegrationTests::device error propagates correctly()")
    @OriginalCase("RequestRegionsIntegrationTests::temporarily sets zero-hop before sending for flood-routed contact()")
    @OriginalCase("RequestRegionsIntegrationTests::requestRegions preserves an unmodeled raw type byte in the temp write()")
    @Test fun originalRegionRequestsKeepSourceHandshakeTagsRoutesAndTypedFailures() = runTest {
        for (scenario in listOf("full", "timeout", "error", "zero-hop", "raw-type")) {
            val shortBudget = scenario != "full" && scenario != "error"
            val configuration = if (shortBudget) {
                SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test",
                    binaryRequestOverallTimeout = 0.2, binaryRequestRetransmitInterval = null)
            } else SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test")
            val transport = MockTransport()
            val session = startSourceSession(transport, configuration, sourceRegionSelfInfo())
            try {
                assertEquals(hex("010320202020202054657374"), transport.sentData.single())
                assertEquals(62, sourceRegionSelfInfo().size)
                assertEquals(hex("05000000"), sourceRegionSelfInfo().prefix(4))
                transport.clearSentData()
                val rawType = scenario == "raw-type"
                val key = Bytes(ByteArray(32) { (if (rawType) 0xcd else 0xdd).toByte() })
                val contact = MeshContact(
                    id = if (rawType) "r" else key.hexString, publicKey = key,
                    type = if (rawType) ContactType.CHAT else ContactType.REPEATER,
                    flags = ContactFlags(0u), outPathLength = 0xffu, outPath = Bytes.EMPTY,
                    advertisedName = if (rawType) "Repeater" else "TestRepeater",
                    lastAdvertisement = if (rawType) Instant.ofEpochSecond(1000) else Instant.EPOCH,
                    latitude = 0.0, longitude = 0.0, lastModified = Instant.EPOCH,
                    typeRawValue = if (rawType) 4u else 2u,
                )
                val startedAt = testScheduler.currentTime
                val call = backgroundScope.async {
                    try {
                        Result.success(session.requestRegions(contact))
                    } catch (failure: MeshCoreException.Timeout) {
                        Result.failure<List<String>>(failure)
                    } catch (failure: MeshCoreException.DeviceError) {
                        Result.failure<List<String>>(failure)
                    }
                }
                runCurrent()
                val temporary = transport.sentData.single()
                assertEquals(147, temporary.size)
                assertEquals(9, temporary[0].toInt())
                assertEquals(key, temporary.slice(1, 33))
                assertEquals(contact.typeRawValue, temporary[33])
                assertEquals(0, temporary[35].toInt())
                val name = Bytes.utf8(contact.advertisedName)
                val tail = Bytes(ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(if (rawType) 1000 else 0).putInt(0).putInt(0).put(ByteArray(3)).array())
                assertEquals(hex("09") + key + Bytes.of(contact.typeRawValue.toInt(), 0, 0) +
                    Bytes(ByteArray(64)) + name + Bytes(ByteArray(32 - name.size)) + tail, temporary)
                assertFalse(call.isCompleted)
                transport.simulateOK()
                runCurrent()
                assertEquals(2, transport.sentData.size)
                assertEquals(hex("39") + key + hex("0100"), transport.sentData.last())
                assertEquals(35, transport.sentData.last().size)
                assertFalse(call.isCompleted)
                when (scenario) {
                    "error" -> {
                        transport.simulateError(10u)
                        runCurrent()
                    }
                    "full" -> {
                        transport.simulateReceive(hex("06000102030488130000"))
                        transport.simulateReceive(hex("8c0001020304ddccbbaa") + Bytes.utf8("Europe,UK,France"))
                        runCurrent()
                    }
                    else -> {
                        val tag = if (scenario == "timeout") hex("deadbeef") else hex("aabbccdd")
                        transport.simulateReceive(hex("0600") + tag + hex("64000000"))
                        runCurrent()
                        advanceTimeBy(199)
                        runCurrent()
                        assertEquals(2, transport.sentData.size)
                        assertFalse(call.isCompleted)
                        advanceTimeBy(1)
                        runCurrent()
                    }
                }
                assertEquals(3, transport.sentData.size)
                assertEquals(hex("0d") + key, transport.sentData.last())
                assertFalse("Restore acknowledgment is still outstanding", call.isCompleted)
                transport.simulateOK()
                runCurrent()
                val outcome = call.await()
                when (scenario) {
                    "full" -> assertEquals(listOf("Europe", "UK", "France"), outcome.getOrThrow())
                    "error" -> {
                        when (val failure = outcome.exceptionOrNull()) {
                            is MeshCoreException.DeviceError -> assertEquals(10u.toUByte(), failure.code)
                            else -> fail("Region error must remain a typed device error, got $failure")
                        }
                    }
                    else -> {
                        assertTrue("Missing region response must remain a typed timeout",
                            outcome.exceptionOrNull() is MeshCoreException.Timeout)
                        assertEquals(200L, testScheduler.currentTime - startedAt)
                    }
                }
                assertEquals(listOf(9, 0x39, 0x0d), transport.sentData.map { it[0].toInt() })
            } finally {
                session.stop()
            }
        }
    }

    @OriginalCase("ProtocolBugFixTests::requestStatus throws device error when error response received()")
    @OriginalCase("ProtocolBugFixTests::requestNeighbours rejects a short public key before sending()")
    @Test fun originalStatusErrorAndUnstartedShortNeighbourKeyKeepSourceInputs() = runTest {
        val configuration = SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test")
        val transport = MockTransport()
        val session = startSourceSession(transport, configuration, sourceRegionSelfInfo())
        try {
            assertEquals(hex("010320202020202054657374"), transport.sentData.single())
            val key = Bytes(ByteArray(32) { 0x31 })
            val call = backgroundScope.async {
                try {
                    session.requestStatus(key)
                    fail("An original status device error cannot become a successful response")
                    null
                } catch (failure: MeshCoreException.DeviceError) {
                    failure
                }
            }
            runCurrent()
            assertEquals(listOf(hex("010320202020202054657374"), hex("1b") + key), transport.sentData)
            assertFalse(call.isCompleted)
            transport.simulateError(10u)
            runCurrent()
            assertEquals(10u.toUByte(), call.await()?.code)
            assertEquals(2, transport.sentData.size)
        } finally {
            session.stop()
        }

        val unstartedTransport = MockTransport()
        val unstarted = MeshCoreSession(
            unstartedTransport, configuration, publicSessionClock(), backgroundScope.coroutineContext,
        )
        try {
            try {
                unstarted.requestNeighbours(Bytes(ByteArray(6) { 0x31 }))
                fail("The original short key must fail before connection admission or any send")
            } catch (_: MeshCoreException.InvalidInput) {
                assertTrue(unstartedTransport.sentData.isEmpty())
            }
            assertTrue(unstartedTransport.sentData.isEmpty())
        } finally {
            unstarted.stop()
        }
    }

    private fun sourceRegionSelfInfo(): Bytes =
        Bytes(ByteBuffer.allocate(62).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x05).put(0).put(0).put(0).put(ByteArray(32) { 1 })
            .putInt(0).putInt(0).put(ByteArray(4)).putInt(915_000).putInt(125_000)
            .put(7).put(5).put("Test".toByteArray(Charsets.UTF_8)).array())

    private fun sourceCorrelationStatus(battery: Short, posted: Short = 0, pushed: Short = 0): Bytes =
        Bytes(ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x87.toByte()).put(0).put(ByteArray(6) { 0x31 })
            .putShort(battery).putShort(0).putShort(-110).putShort(-85)
            .putInt(100).putInt(50).putInt(25).putInt(3600)
            .putInt(5).putInt(10).putInt(15).putInt(20)
            .putShort(0).putShort(0).putShort(0).putShort(0).putShort(posted).putShort(pushed).array())

    private fun sourceCorrelationContact(keyByte: Int, name: String): Bytes =
        Bytes.of(0x03) + Bytes(ByteArray(32) { keyByte.toByte() }) + hex("0100ff") +
            Bytes(ByteArray(64)) + Bytes.utf8(name) + Bytes(ByteArray(32 - name.length)) + Bytes(ByteArray(16))

    private fun sourceAutomaticSelfInfo(): Bytes =
        hex("05011416" + "aa".repeat(32) + "00".repeat(8) +
            "00000001bde40d0024f400000705546573744e6f6465")

    private fun sourcePipelineConfiguration(window: Long = 8, idleTimeout: Double = 1.5) =
        SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst",
            channelPipelineWindow = window, channelPipelineIdleTimeout = idleTimeout,
            channelPipelineHardTimeout = 5.0, channelPipelinePostDrainGrace = 0.02)

    private suspend fun TestScope.startSourceSession(
        transport: MockTransport,
        configuration: SessionConfiguration = SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"),
        selfInfo: Bytes = sourceSelfInfo(869_525, 250_000, 11, ""),
    ): MeshCoreSession {
        val session = MeshCoreSession(transport, configuration, publicSessionClock(), backgroundScope.coroutineContext)
        val starting = backgroundScope.async { session.start() }
        runCurrent()
        assertEquals(1, transport.sentData.size)
        assertFalse(starting.isCompleted)
        transport.simulateReceive(selfInfo)
        runCurrent()
        starting.await()
        assertNotNull(session.currentSelfInfo)
        return session
    }

    private fun sourceSelfInfo(frequency: Int, bandwidth: Int, spreadingFactor: Byte, name: String): Bytes {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        return Bytes(ByteBuffer.allocate(58 + nameBytes.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(0x05).put(1).put(22).put(22).put(ByteArray(32) { 1 })
            .putInt(0).putInt(0).put(ByteArray(4))
            .putInt(frequency).putInt(bandwidth).put(spreadingFactor).put(5).put(nameBytes).array())
    }

    private fun sourceChannel(index: Int, name: String, secret: Int = 0): Bytes =
        Bytes.of(0x12, index) + Bytes.utf8(name) + Bytes(ByteArray(32 - name.length)) +
            Bytes(ByteArray(16) { secret.toByte() })

    private suspend fun TestScope.delayedOk(
        transport: MockTransport, expected: String, operation: suspend () -> Unit,
    ) {
        val call = backgroundScope.async { operation() }
        runCurrent()
        assertEquals(hex(expected), transport.sentData.last())
        assertFalse("Written command must still await OK", call.isCompleted)
        transport.simulateReceive(hex("0ce803"))
        advanceTimeBy(100)
        runCurrent()
        assertFalse("An unrelated event is not an acknowledgment", call.isCompleted)
        transport.simulateOK()
        runCurrent()
        call.await()
    }

    private suspend fun TestScope.delayedError(
        transport: MockTransport, expected: String, code: UByte, operation: suspend () -> Any?,
    ) {
        val call = backgroundScope.async {
            try {
                operation()
                error("A missing/error response cannot become success or nullable absence")
            } catch (failure: MeshCoreException.DeviceError) {
                failure
            }
        }
        runCurrent()
        assertEquals(hex(expected), transport.sentData.last())
        advanceTimeBy(100)
        runCurrent()
        assertFalse(call.isCompleted)
        transport.simulateError(code)
        runCurrent()
        assertEquals(code, call.await().code)
    }

    private fun hex(value: String): Bytes = Bytes.fromHex(value)
}

@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.publicSessionClock(): SessionClock = object : SessionClock {
    override val now: Duration get() = testScheduler.currentTime.milliseconds
    override val wallClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    override suspend fun sleepFor(duration: Duration) = delay(duration)
}
