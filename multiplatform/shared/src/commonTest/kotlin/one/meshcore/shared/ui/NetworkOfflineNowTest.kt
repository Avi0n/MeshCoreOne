package one.meshcore.shared.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkOfflineNowTest {
    @Test
    fun aMissingFlowUsesTheSynchronousProbe() {
        val offline = ShellPreferenceInputs(isNetworkOffline = { true })
        val online = ShellPreferenceInputs(isNetworkOffline = { false })
        assertTrue(offline.networkOfflineNow())
        assertFalse(online.networkOfflineNow())
    }

    @Test
    fun aLiveFlowWinsOverTheProbe() {
        val flow = MutableStateFlow(false)
        val host = ShellPreferenceInputs(isNetworkOffline = { true }, networkOffline = flow)
        assertFalse(host.networkOfflineNow())
        flow.value = true
        assertTrue(host.networkOfflineNow())
    }
}
