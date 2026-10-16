package one.meshcore.shared.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import one.meshcore.shared.connection.CompanionAssociateResult
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.DeviceConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnboardingCompanionPairingTest {
    @Test
    fun missingDemoStoreStartsDisabled() {
        val live = MutableStateFlow(true)
        assertEquals(live, demoModeEnabledState(live))
        assertEquals(false, demoModeEnabledState(null).value)
    }

    @Test
    fun connectingSkipsASecondPairingAttempt() = runBlocking {
        val calls = mutableListOf<String>()
        startOnboardingCompanionPairing(
            connecting = true,
            stopScan = { calls += "stop" },
            scope = this,
            session = {
                calls += "session"
                pairing(calls)
            },
        )
        completeFreshOnboardingPairing(
            connecting = true,
            setPairingInProgress = { calls += "progress" },
            stopScan = { calls += "stop" },
            scope = this,
            session = {
                calls += "session"
                pairing(calls)
            },
        )
        assertTrue(calls.isEmpty())
    }

    @Test
    fun leftoversWaitAndAMissingChooserScansInApp() = runBlocking {
        val calls = mutableListOf<String>()
        startOnboardingCompanionPairing(
            connecting = false,
            stopScan = { calls += "stop" },
            scope = this,
            session = { pairing(calls, associations = listOf(CompanionAssociation("aa", "Ada"))) },
        )
        yield()
        assertEquals(listOf("stop", "pending"), calls.toList())
        calls.clear()
        startOnboardingCompanionPairing(
            connecting = false,
            stopScan = { calls += "stop" },
            scope = this,
            session = { pairing(calls, companionAvailable = false) },
        )
        yield()
        assertEquals(listOf("stop", "progress", "scan"), calls.toList())
    }

    @Test
    fun companionResultsConnectHoldOrScan() = runBlocking {
        val calls = mutableListOf<String>()
        consumeOnboardingCompanionResult(
            pairing(calls, held = true),
            CompanionAssociateResult.Associated(CompanionAssociation("aa", "Ada")),
        )
        assertEquals(listOf("held:aa"), calls.toList())
        calls.clear()
        consumeOnboardingCompanionResult(pairing(calls), CompanionAssociateResult.Cancelled)
        assertEquals(listOf("cancelled"), calls.toList())
        calls.clear()
        consumeOnboardingCompanionResult(pairing(calls), CompanionAssociateResult.Unavailable)
        assertEquals(listOf("scan"), calls.toList())
    }

    @Test
    fun failedConnectClearsTheBusyFlag() = runBlocking {
        val calls = mutableListOf<String>()
        launchPairConnect(
            scope = this,
            connect = {
                calls += "connect"
                false
            },
            onSuccess = { calls += "ok" },
            setConnecting = { calls += "busy:$it" },
        )
        yield()
        assertEquals(listOf("busy:true", "connect", "busy:false"), calls.toList())
    }

    @Test
    fun forgetDropsEachLeftoverHandle() {
        val handles = mutableListOf<String>()
        forgetLeftoverCompanionAccessories(
            listOf(CompanionAssociation("aa", "Ada"), CompanionAssociation("bb", "Bea")),
        ) { handles += it }
        assertEquals(listOf("aa", "bb"), handles.toList())
    }

    @Test
    fun pickerAndScannedConnectWaitUntilIdle() {
        val calls = mutableListOf<String>()
        openOnboardingConnectPicker(
            show = false,
            consumePicker = { calls += "picker" },
            consumeCompanion = { calls += "companion" },
            begin = { calls += "begin" },
        )
        openOnboardingConnectPicker(
            show = true,
            consumePicker = { calls += "picker" },
            consumeCompanion = { calls += "companion" },
            begin = { calls += "begin" },
        )
        startScannedDeviceConnect(
            connecting = true,
            stopScan = { calls += "stop" },
            connect = { calls += "connect" },
        )
        startScannedDeviceConnect(
            connecting = false,
            stopScan = { calls += "stop" },
            connect = { calls += "connect" },
        )
        assertEquals(listOf("picker", "companion", "begin", "stop", "connect"), calls.toList())
    }

    private fun pairing(
        calls: MutableList<String>,
        associations: List<CompanionAssociation> = emptyList(),
        companionAvailable: Boolean = true,
        held: Boolean = false,
    ) = OnboardingCompanionPairing(
        associations = associations,
        savedDevices = emptyList(),
        connectionState = DeviceConnectionState.DISCONNECTED,
        lastConnectedId = null,
        isRadioLinked = false,
        companionAvailable = companionAvailable,
        bluetoothGranted = true,
        disconnect = {},
        noteWantsConnection = {},
        setPendingSetup = { calls += "pending" },
        setPairingInProgress = { calls += "progress" },
        requestBluetooth = { calls += "bluetooth" },
        showInAppScan = { calls += "scan" },
        associate = {
            calls += "associate"
            CompanionAssociateResult.Cancelled
        },
        isHeld = { held },
        onHeld = { calls += "held:$it" },
        onConnect = { calls += "connect:$it" },
        onCancelled = { calls += "cancelled" },
    )
}
