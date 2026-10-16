package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.RepeaterSettingsController
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class NodeRegionLifetimeTest {
    @Test
    fun cancellationAfterRemoveAckPublishesTheAcceptedRemoval() = runTest {
        val harness = RemoteAdminTestHarness(backgroundScope)
        val send: suspend (String, Duration) -> String = { command, _ ->
            when (command) {
                "region" -> "* F\n pond F\n"
                "region default" -> "default scope is pond"
                "region remove pond" -> "OK"
                "region default <null>" -> throw CancellationException("replaced")
                else -> error("Unexpected command: $command")
            }
        }
        val helper = NodeSettingsController(send, send).also { it.setNodeInfo("1.15.0", null, null) }
        val controller = RepeaterSettingsController(helper, send, send)
        val state = MutableStateFlow(NodeSettingsState())
        val commands = NodeRegionCommands(
            backgroundScope,
            state,
            NodeSettingsViewModel.Dependencies { harness.repeater },
            settings = { controller },
            reportedMissingService = { false },
            canMutateRegions = { true },
        )
        commands.loadRegions()
        runCurrent()
        assertTrue(state.value.regions.any { it.name == "pond" })
        commands.removeRegion("pond")
        runCurrent()
        assertFalse(state.value.regions.any { it.name == "pond" })
        assertTrue(state.value.hasUnsavedRegionChanges)
        assertNull(state.value.applyBanner)
    }
}
