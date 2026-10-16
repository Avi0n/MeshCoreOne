package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.contact.StaleNodeRemovalResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DangerZoneSettingsDecisionsTest {
    @Test
    fun embeddedSectionTitleAndButtonGates() {
        assertEquals("Danger", dangerZoneSectionTitle(embedded = true, title = "Danger"))
        assertEquals(null, dangerZoneSectionTitle(embedded = false, title = "Danger"))
        assertTrue(dangerZoneResetEnabled(isDeviceConnected = true, isResetting = false))
        assertFalse(dangerZoneResetEnabled(isDeviceConnected = false, isResetting = false))
        assertFalse(dangerZoneResetEnabled(isDeviceConnected = true, isResetting = true))
        assertTrue(
            dangerZoneRemoveEnabled(
                isDeviceConnected = true,
                isRemoving = false,
                showRemoveSuccess = false,
            ),
        )
        assertFalse(dangerZoneRemoveEnabled(isDeviceConnected = false, isRemoving = false, showRemoveSuccess = false))
        assertFalse(dangerZoneRemoveEnabled(isDeviceConnected = true, isRemoving = true, showRemoveSuccess = false))
        assertFalse(dangerZoneRemoveEnabled(isDeviceConnected = true, isRemoving = false, showRemoveSuccess = true))
    }

    @Test
    fun removeLabelTracksBusyAndSuccess() {
        assertEquals(
            DangerZoneRemoveLabel.Counting,
            dangerZoneRemoveLabel(isRemoving = true, pendingRemoveCount = null, showRemoveSuccess = false),
        )
        assertEquals(
            DangerZoneRemoveLabel.Removing,
            dangerZoneRemoveLabel(isRemoving = true, pendingRemoveCount = 2, showRemoveSuccess = false),
        )
        assertEquals(
            DangerZoneRemoveLabel.Success,
            dangerZoneRemoveLabel(isRemoving = false, pendingRemoveCount = null, showRemoveSuccess = true),
        )
        assertEquals(
            DangerZoneRemoveLabel.Idle,
            dangerZoneRemoveLabel(isRemoving = false, pendingRemoveCount = null, showRemoveSuccess = false),
        )
        assertEquals(
            DangerZoneRemoveLabel.Counting,
            dangerZoneRemoveLabel(isRemoving = true, pendingRemoveCount = null, showRemoveSuccess = true),
        )
    }

    @Test
    fun unfavoritedCountSkipsWhenDisabledAndReportsZeroOrSome() = runBlocking {
        val calls = mutableListOf<String>()
        val skipped = launchDangerZoneUnfavoritedCount(
            enabled = false,
            scope = this,
            unfavoritedCount = {
                calls += "count"
                2
            },
            callbacks = countCallbacks(calls),
        )
        assertTrue(skipped.isCompleted)
        assertEquals(emptyList(), calls.toList())

        val none = launchDangerZoneUnfavoritedCount(
            enabled = true,
            scope = this,
            unfavoritedCount = { 0 },
            callbacks = countCallbacks(calls),
        )
        none.join()
        assertEquals(listOf("removing:true", "none", "removing:false"), calls.toList())

        calls.clear()
        val some = launchDangerZoneUnfavoritedCount(
            enabled = true,
            scope = this,
            unfavoritedCount = { 2 },
            callbacks = countCallbacks(calls),
        )
        some.join()
        assertEquals(listOf("removing:true", "some:2", "removing:false"), calls.toList())
    }

    @Test
    fun unfavoritedCountFailureAndCancellationClearBusy() = runBlocking {
        val calls = mutableListOf<String>()
        val failed = launchDangerZoneUnfavoritedCount(
            enabled = true,
            scope = this,
            unfavoritedCount = { error("offline") },
            callbacks = countCallbacks(calls),
        )
        failed.join()
        assertEquals(listOf("removing:true", "failure:offline", "removing:false"), calls.toList())

        calls.clear()
        val stop = CancellationException("stop")
        var cause: Throwable? = null
        val cancelled = launchDangerZoneUnfavoritedCount(
            enabled = true,
            scope = this,
            unfavoritedCount = { throw stop },
            callbacks = countCallbacks(calls),
        )
        cancelled.invokeOnCompletion { cause = it }
        cancelled.join()
        assertSame(stop, cause)
        assertEquals(listOf("removing:true", "removing:false"), calls.toList())
    }

    @Test
    fun forgetLeavesOnlyAfterTheAction() = runBlocking {
        val calls = mutableListOf<String>()
        runDangerZoneForget(
            action = { calls += "keep" },
            onLeft = { calls += "leave" },
            onFailure = { calls += "failure" },
        )
        assertEquals(listOf("keep", "leave"), calls.toList())

        calls.clear()
        runDangerZoneForget(
            action = { error("full") },
            onLeft = { calls += "leave" },
            onFailure = { calls += "failure:${it.message}" },
        )
        assertEquals(listOf("failure:full"), calls.toList())

        calls.clear()
        val stop = CancellationException("stop")
        var cause: Throwable? = null
        val cancelled = launch {
            runDangerZoneForget(
                action = { throw stop },
                onLeft = { calls += "leave" },
                onFailure = { calls += "failure" },
            )
        }
        cancelled.invokeOnCompletion { cause = it }
        cancelled.join()
        assertSame(stop, cause)
        assertEquals(emptyList(), calls.toList())
    }

    @Test
    fun factoryResetLeavesOnlyWhenTheRadioAgrees() = runBlocking {
        val calls = mutableListOf<String>()
        runDangerZoneFactoryReset(
            reset = {
                calls += "reset"
                true
            },
            onLeft = { calls += "leave" },
            onUnavailable = { calls += "unavailable" },
            onFailure = { calls += "failure:${it.message}" },
            setResetting = { calls += "resetting:$it" },
        )
        assertEquals(listOf("resetting:true", "reset", "leave", "resetting:false"), calls.toList())

        calls.clear()
        runDangerZoneFactoryReset(
            reset = { false },
            onLeft = { calls += "leave" },
            onUnavailable = { calls += "unavailable" },
            onFailure = { calls += "failure" },
            setResetting = { calls += "resetting:$it" },
        )
        assertEquals(listOf("resetting:true", "unavailable", "resetting:false"), calls.toList())

        calls.clear()
        runDangerZoneFactoryReset(
            reset = { error("busy") },
            onLeft = { calls += "leave" },
            onUnavailable = { calls += "unavailable" },
            onFailure = { calls += "failure:${it.message}" },
            setResetting = { calls += "resetting:$it" },
        )
        assertEquals(listOf("resetting:true", "failure:busy", "resetting:false"), calls.toList())

        calls.clear()
        val stop = CancellationException("stop")
        var cause: Throwable? = null
        val cancelled = launch {
            runDangerZoneFactoryReset(
                reset = { throw stop },
                onLeft = { calls += "leave" },
                onUnavailable = { calls += "unavailable" },
                onFailure = { calls += "failure" },
                setResetting = { calls += "resetting:$it" },
            )
        }
        cancelled.invokeOnCompletion { cause = it }
        cancelled.join()
        assertSame(stop, cause)
        assertEquals(listOf("resetting:true", "resetting:false"), calls.toList())
    }

    @Test
    fun removalReportsPartialWhenAnyNodeStays() = runBlocking {
        val calls = mutableListOf<String>()
        applyDangerZoneRemoval(
            remove = { StaleNodeRemovalResult(removed = 2, total = 2) },
            onFull = { calls += "full" },
            onPartial = { calls += "partial:${it.removed}/${it.total}" },
            onFailure = { calls += "failure" },
            setRemoving = { calls += "removing:$it" },
        )
        assertEquals(listOf("removing:true", "full", "removing:false"), calls.toList())

        calls.clear()
        applyDangerZoneRemoval(
            remove = { StaleNodeRemovalResult(removed = 1, total = 2) },
            onFull = { calls += "full" },
            onPartial = { calls += "partial:${it.removed}/${it.total}" },
            onFailure = { calls += "failure:${it.message}" },
            setRemoving = { calls += "removing:$it" },
        )
        assertEquals(listOf("removing:true", "partial:1/2", "removing:false"), calls.toList())

        calls.clear()
        val stop = CancellationException("stop")
        var cause: Throwable? = null
        val cancelled = launch {
            applyDangerZoneRemoval(
                remove = { throw stop },
                onFull = { calls += "full" },
                onPartial = { calls += "partial" },
                onFailure = { calls += "failure" },
                setRemoving = { calls += "removing:$it" },
            )
        }
        cancelled.invokeOnCompletion { cause = it }
        cancelled.join()
        assertSame(stop, cause)
        assertEquals(listOf("removing:true", "removing:false"), calls.toList())
    }

    @Test
    fun anErrorPropagatesWithoutAFailure() = runBlocking {
        val calls = mutableListOf<String>()
        var thrown: Throwable? = null
        val probe = DangerZoneProbeError()
        val scope = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, error -> thrown = error })
        val counted = launchDangerZoneUnfavoritedCount(
            enabled = true,
            scope = scope,
            unfavoritedCount = { throw probe },
            callbacks = countCallbacks(calls),
        )
        counted.join()
        assertSame(probe, thrown)
        assertEquals(listOf("removing:true", "removing:false"), calls.toList())

        calls.clear()
        val forgotten = DangerZoneProbeError()
        val forgetThrown = assertFailsWith<DangerZoneProbeError> {
            runDangerZoneForget(
                action = { throw forgotten },
                onLeft = { calls += "leave" },
                onFailure = { calls += "failure" },
            )
        }
        assertSame(forgotten, forgetThrown)
        assertEquals(emptyList(), calls.toList())

        val resetProbe = DangerZoneProbeError()
        val resetThrown = assertFailsWith<DangerZoneProbeError> {
            runDangerZoneFactoryReset(
                reset = { throw resetProbe },
                onLeft = { calls += "leave" },
                onUnavailable = { calls += "unavailable" },
                onFailure = { calls += "failure" },
                setResetting = { calls += "resetting:$it" },
            )
        }
        assertSame(resetProbe, resetThrown)
        assertEquals(listOf("resetting:true", "resetting:false"), calls.toList())

        calls.clear()
        val removalProbe = DangerZoneProbeError()
        val removalThrown = assertFailsWith<DangerZoneProbeError> {
            applyDangerZoneRemoval(
                remove = { throw removalProbe },
                onFull = { calls += "full" },
                onPartial = { calls += "partial" },
                onFailure = { calls += "failure" },
                setRemoving = { calls += "removing:$it" },
            )
        }
        assertSame(removalProbe, removalThrown)
        assertEquals(listOf("removing:true", "removing:false"), calls.toList())
    }

    private fun countCallbacks(calls: MutableList<String>) = DangerZoneCountCallbacks(
        onNone = { calls += "none" },
        onSome = { calls += "some:$it" },
        onFailure = { calls += "failure:${it.message}" },
        setRemoving = { calls += "removing:$it" },
    )
}

private class DangerZoneProbeError : Error("probe")
