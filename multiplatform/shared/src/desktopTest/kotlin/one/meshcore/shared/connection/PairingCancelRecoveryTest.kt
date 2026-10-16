package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.DeviceEntity
import one.meshcore.shared.simulator.MockDataProvider
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PairingCancelRecoveryTest {
    @Test
    fun cancellingTheNewChooserRestoresThePriorBleRadioAndPartition() = withFixture { fixture ->
        val saved = fixture.connectBle()
        fixture.gateRecovery()
        assertTrue(fixture.prepareChooser())
        assertEquals(ConnectionState.Disconnected, fixture.manager.state.value)
        assertEquals(listOf(PairingCancelFixture.HANDLE), fixture.bleTargets)

        val release = fixture.endPairing()
        fixture.awaitRecoveryEntry()
        assertEquals(listOf(PairingCancelFixture.HANDLE, PairingCancelFixture.HANDLE), fixture.bleTargets)
        fixture.releaseRecovery()
        fixture.join(release)
        fixture.awaitReady(PairingCancelFixture.HANDLE)

        fixture.onManager {
            assertPreserved(fixture, saved, PairingCancelFixture.HANDLE)
            assertEquals(1, fixture.mintedRadioIds)
            assertEquals(saved.radioID, fixture.manager.radioId)
            assertTrue(fixture.wifiTargets.isEmpty())
        }
    }

    @Test
    fun anExplicitUserDisconnectDoesNotResumeWhenPairingEnds() = withFixture { fixture ->
        fixture.connectBle()
        fixture.onManager { fixture.manager.disconnect() }
        fixture.join(fixture.endPairing())

        fixture.onManager {
            assertEquals(ConnectionState.Disconnected, fixture.manager.state.value)
            assertEquals(listOf(PairingCancelFixture.HANDLE), fixture.bleTargets)
            assertFalse(fixture.manager.wantsConnection)
            assertTrue(fixture.intent.restore().isUserDisconnected)
            assertFalse(fixture.manager.isBleReconnectInProgress())
        }
    }

    @Test
    fun acceptingAnotherRadioBeforeSheetDisposalKeepsItsNewGraph() = withFixture { fixture ->
        fixture.connectBle()
        assertTrue(fixture.prepareChooser())
        fixture.onManager { fixture.manager.connect(PairingCancelFixture.OTHER_HANDLE) }
        val acceptedGraph = fixture.manager.services
        fixture.join(fixture.endPairing())

        fixture.onManager {
            assertEquals(ConnectionState.Ready(PairingCancelFixture.OTHER_HANDLE), fixture.manager.state.value)
            assertSame(acceptedGraph, fixture.manager.services)
            assertEquals(listOf(PairingCancelFixture.HANDLE, PairingCancelFixture.OTHER_HANDLE), fixture.bleTargets)
            assertEquals(PairingCancelFixture.OTHER_HANDLE, fixture.lastConnection.handle())
            assertEquals(PairingCancelFixture.OTHER_RADIO_ID, fixture.manager.radioId)
            assertFalse(fixture.manager.isBleReconnectInProgress())
        }
    }

    @Test
    fun cancellingTheNewChooserRestoresWifiWithoutAllocatingBle() = withFixture { fixture ->
        val saved = fixture.connectWifi()
        fixture.gateRecovery()
        assertTrue(fixture.prepareChooser())
        val release = fixture.endPairing()
        fixture.awaitRecoveryEntry()
        assertEquals(
            listOf(
                PairingCancelFixture.HOST to PairingCancelFixture.PORT,
                PairingCancelFixture.HOST to PairingCancelFixture.PORT,
            ),
            fixture.wifiTargets,
        )
        fixture.releaseRecovery()
        fixture.join(release)
        fixture.awaitReady(PairingCancelFixture.WIFI_HANDLE)

        fixture.onManager {
            assertPreserved(fixture, saved, PairingCancelFixture.WIFI_HANDLE)
            assertTrue(fixture.bleTargets.isEmpty())
            assertEquals(TransportType.WIFI, fixture.manager.currentTransportType)
            assertEquals(PairingCancelFixture.HOST, fixture.manager.wifiHost)
            assertEquals(PairingCancelFixture.PORT, fixture.manager.wifiPort)
        }
    }

    @Test
    fun cancellingTheNewChooserRestoresTheSimulatorWithoutAllocatingBle() = withFixture { fixture ->
        fixture.onManager { fixture.manager.simulatorConnect() }
        val saved = fixture.database.deviceDao().getById(MockDataProvider.simulatorDeviceID)!!
        assertTrue(fixture.prepareChooser())
        fixture.join(fixture.endPairing())
        fixture.awaitReady(MockDataProvider.SIMULATOR_HANDLE)

        fixture.onManager {
            assertPreserved(fixture, saved, MockDataProvider.SIMULATOR_HANDLE)
            assertTrue(fixture.bleTargets.isEmpty())
            assertTrue(fixture.wifiTargets.isEmpty())
            assertEquals(saved.radioID, fixture.manager.radioId)
        }
    }

    @Test
    fun repeatedSheetDisposalDoesNotStartAnotherRecovery() = withFixture { fixture ->
        fixture.connectBle()
        fixture.gateRecovery()
        assertTrue(fixture.prepareChooser())
        val first = fixture.endPairing()
        fixture.awaitRecoveryEntry()
        val second = fixture.endPairing()
        fixture.releaseRecovery()
        fixture.join(first)
        fixture.join(second)
        fixture.awaitReady(PairingCancelFixture.HANDLE)

        fixture.onManager {
            assertEquals(listOf(PairingCancelFixture.HANDLE, PairingCancelFixture.HANDLE), fixture.bleTargets)
            assertEquals(1, fixture.mintedRadioIds)
        }
    }

    @Test
    fun cancellingPreparationDuringReadyTeardownWaitsBeforeRestoringThePriorRadio() = withFixture { fixture ->
        val saved = fixture.connectBle()
        fixture.gateRecovery()
        val (teardownEntered, allowTeardown) = fixture.holdOldTeardown()
        try {
            val prepare = fixture.launchOnManager { assertTrue(fixture.prepareChooser()) }
            withTimeout(PairingCancelFixture.TIMEOUT_MS) { teardownEntered.await() }
            prepare.cancel()
            val release = fixture.endPairing()

            fixture.onManager {
                assertTrue(release?.isActive == true, "sheet disposal must wait for the owned Ready teardown")
                assertEquals(listOf(PairingCancelFixture.HANDLE), fixture.bleTargets)
            }
            allowTeardown.complete(Unit)
            assertFailsWith<CancellationException> { fixture.join(prepare) }
            fixture.awaitRecoveryEntry()
            fixture.releaseRecovery()
            fixture.join(release)
            fixture.awaitReady(PairingCancelFixture.HANDLE)

            fixture.onManager {
                assertEquals(listOf(PairingCancelFixture.HANDLE, PairingCancelFixture.HANDLE), fixture.bleTargets)
                assertPreserved(fixture, saved, PairingCancelFixture.HANDLE)
                assertEquals(saved.radioID, fixture.manager.radioId)
            }
        } finally {
            allowTeardown.complete(Unit)
        }
    }

    @Test
    fun anOldPairingTeardownCannotDiscardANewerAcceptedRadio() = withFixture { fixture ->
        fixture.connectBle()
        val (teardownEntered, allowTeardown) = fixture.holdOldTeardown()
        try {
            val prepare = fixture.launchOnManager { assertTrue(fixture.prepareChooser()) }
            withTimeout(PairingCancelFixture.TIMEOUT_MS) { teardownEntered.await() }
            val accept = fixture.launchOnManager { fixture.manager.connect(PairingCancelFixture.OTHER_HANDLE) }
            fixture.onManager { assertEquals(PairingCancelFixture.OTHER_HANDLE, fixture.manager.currentDeviceId) }
            allowTeardown.complete(Unit)
            assertFailsWith<CancellationException> { fixture.join(prepare) }
            fixture.join(accept)
            val acceptedGraph = fixture.manager.services
            fixture.join(fixture.endPairing())

            fixture.onManager {
                assertEquals(ConnectionState.Ready(PairingCancelFixture.OTHER_HANDLE), fixture.manager.state.value)
                assertSame(acceptedGraph, fixture.manager.services)
                assertEquals(PairingCancelFixture.OTHER_HANDLE, fixture.lastConnection.handle())
                assertEquals(PairingCancelFixture.OTHER_RADIO_ID, fixture.manager.radioId)
                assertEquals(listOf(PairingCancelFixture.HANDLE, PairingCancelFixture.OTHER_HANDLE), fixture.bleTargets)
                assertEquals(0, fixture.noteWantedCalls, "abandoned preparation must not resume its chooser")
            }
        } finally {
            allowTeardown.complete(Unit)
        }
    }

    @Test
    fun preparationWaitingForAnOlderTransitionCannotRetargetANewerAcceptedRadio() = withFixture { fixture ->
        fixture.connectBle()
        val (teardownEntered, allowTeardown) = fixture.holdOldTeardown()
        try {
            val first = fixture.launchOnManager { assertTrue(fixture.prepareChooser()) }
            withTimeout(PairingCancelFixture.TIMEOUT_MS) { teardownEntered.await() }
            val waiting = fixture.launchOnManager { assertTrue(fixture.prepareChooser()) }
            val accept = fixture.launchOnManager { fixture.manager.connect(PairingCancelFixture.OTHER_HANDLE) }
            fixture.onManager { assertEquals(PairingCancelFixture.OTHER_HANDLE, fixture.manager.currentDeviceId) }
            allowTeardown.complete(Unit)
            assertFailsWith<CancellationException> { fixture.join(first) }
            assertFailsWith<CancellationException> { fixture.join(waiting) }
            fixture.join(accept)
            val acceptedGraph = fixture.manager.services
            fixture.join(fixture.endPairing())

            fixture.onManager {
                assertEquals(ConnectionState.Ready(PairingCancelFixture.OTHER_HANDLE), fixture.manager.state.value)
                assertSame(acceptedGraph, fixture.manager.services)
                assertEquals(PairingCancelFixture.OTHER_HANDLE, fixture.lastConnection.handle())
                assertEquals(PairingCancelFixture.OTHER_RADIO_ID, fixture.manager.radioId)
                assertEquals(listOf(PairingCancelFixture.HANDLE, PairingCancelFixture.OTHER_HANDLE), fixture.bleTargets)
                assertEquals(0, fixture.noteWantedCalls, "superseded preparations must not resume their choosers")
            }
        } finally {
            allowTeardown.complete(Unit)
        }
    }

    private suspend fun assertPreserved(fixture: PairingCancelFixture, saved: DeviceEntity, handle: String) {
        val current = fixture.database.deviceDao().getById(saved.id)!!
        assertEquals(saved.radioID, current.radioID)
        assertContentEquals(saved.publicKey, current.publicKey)
        assertEquals(saved.id, fixture.lastConnection.deviceId())
        assertEquals(saved.radioID, fixture.lastConnection.radioId())
        assertEquals(handle, fixture.lastConnection.handle())
        assertTrue(fixture.manager.wantsConnection)
        assertFalse(fixture.intent.restore().isUserDisconnected)
        assertTrue(fixture.database.pendingSendDao().listByRadio(saved.radioID).isEmpty())
        assertTrue(fixture.database.pendingSendDao().listOrphans().isEmpty())
    }

    private fun withFixture(block: suspend (PairingCancelFixture) -> Unit) = runBlocking {
        val fixture = PairingCancelFixture()
        try {
            withTimeout(PairingCancelFixture.TIMEOUT_MS) { block(fixture) }
        } finally {
            fixture.close()
        }
    }
}
