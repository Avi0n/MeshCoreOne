package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelJoinFloodScopeApplierTest {
    private val channel = channelDto(
        id = ChannelTestIds.fresh(),
        radioID = ChannelTestIds.RADIO,
        index = 1,
        name = "Ops",
    )

    @Test
    fun preferred_flood_scope_maps_a_non_empty_region_to_region_and_ignores_empty() {
        assertEquals(ChannelFloodScope.Region("emea"), ChannelJoinFloodScopeApplier.preferredFloodScope("emea"))
        assertNull(ChannelJoinFloodScopeApplier.preferredFloodScope(""))
        assertNull(ChannelJoinFloodScopeApplier.preferredFloodScope(null))
    }

    @Test
    fun applies_the_region_and_returns_the_updated_channel() = runTest {
        var written: Pair<Uuid, ChannelFloodScope>? = null
        val result = ChannelJoinFloodScopeApplier.applyIfNeeded(channel, "emea") { id, scope -> written = id to scope }

        assertEquals(channel.id to ChannelFloodScope.Region("emea"), written)
        assertEquals(ChannelFloodScope.Region("emea"), result.floodScope)
    }

    @Test
    fun a_missing_region_writes_nothing_and_returns_the_channel_unchanged() = runTest {
        var called = false
        val result = ChannelJoinFloodScopeApplier.applyIfNeeded(channel, null) { _, _ -> called = true }

        assertTrue(!called)
        assertEquals(ChannelFloodScope.Inherit, result.floodScope)
    }

    @Test
    fun a_failed_write_is_swallowed_and_the_channel_is_returned_unchanged() = runTest {
        val result = ChannelJoinFloodScopeApplier.applyIfNeeded(channel, "emea") { _, _ ->
            error("persist failed")
        }

        assertEquals(ChannelFloodScope.Inherit, result.floodScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun a_failed_write_records_the_ios_persist_dump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val result = ChannelJoinFloodScopeApplier.applyIfNeeded(
            channel,
            "emea",
            PersistentLogger(
                scope = this,
                category = "ChannelJoinFloodScopeApplier",
                record = { recorded += it },
            ),
        ) { _, _ -> error("persist failed") }

        assertEquals(ChannelFloodScope.Inherit, result.floodScope)
        val line = recorded.single { it.message.contains(FAILED_PERSIST_CHANNEL_FLOOD_AFTER_JOIN) }
        assertEquals(DebugLogLevel.ERROR, line.level)
        assertTrue(line.message.contains("persist failed"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun a_cancelled_write_propagates_without_a_persist_dump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val cancelled = CancellationException("left")
        val thrown = assertFailsWith<CancellationException> {
            ChannelJoinFloodScopeApplier.applyIfNeeded(
                channel,
                "emea",
                persistLogger(recorded),
            ) { _, _ -> throw cancelled }
        }

        assertSame(cancelled, thrown)
        assertEquals(emptyList(), recorded)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun a_write_error_propagates_without_a_persist_dump() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val fault = ChannelFloodPersistProbeError()
        val thrown = assertFailsWith<ChannelFloodPersistProbeError> {
            ChannelJoinFloodScopeApplier.applyIfNeeded(
                channel,
                "emea",
                persistLogger(recorded),
            ) { _, _ -> throw fault }
        }

        assertSame(fault, thrown)
        assertEquals(emptyList(), recorded)
    }

    private fun TestScope.persistLogger(recorded: MutableList<DebugLogEntryDto>): PersistentLogger = PersistentLogger(
        scope = this,
        category = "ChannelJoinFloodScopeApplier",
        record = { recorded += it },
    )
}

private class ChannelFloodPersistProbeError : Error("probe")
