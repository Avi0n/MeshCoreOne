package one.meshcore.shared.connection

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionAssociationTest {
    private val radio = CompanionAssociation(handle = "AA:BB:CC:DD:EE:FF", name = "Kitchen")
    private val other = CompanionAssociation(handle = "11:22:33:44:55:66", name = "Shed")

    @Test
    fun leftoverOmitsSavedRadiosAndProtectedHandles() {
        val leftovers = leftoverCompanionAssociations(
            associations = listOf(radio, other),
            savedBleHandles = listOf("aa:bb:cc:dd:ee:ff"),
            protectedHandles = emptyList(),
        )
        assertEquals(listOf(other), leftovers)
    }

    @Test
    fun pairingLeftoversOmitASavedRadioWhileDisconnected() {
        val leftovers = leftoverCompanionAssociationsForPairing(
            associations = listOf(radio, other),
            savedDevices = listOf(saved("AA:BB:CC:DD:EE:FF")),
            connectionState = DeviceConnectionState.DISCONNECTED,
            lastConnectedId = null,
        )
        assertEquals(listOf(other), leftovers)
    }

    @Test
    fun leftoverOmitsTheLiveConnectionEvenWithoutADeviceRow() {
        val leftovers = leftoverCompanionAssociations(
            associations = listOf(radio),
            savedBleHandles = emptyList(),
            protectedHandles = listOf("AA:BB:CC:DD:EE:FF"),
        )
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun leftoverSkipsBlankHandles() {
        val leftovers = leftoverCompanionAssociations(
            associations = listOf(CompanionAssociation(handle = "  ", name = "Ghost")),
            savedBleHandles = emptyList(),
        )
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun aDisconnectedSessionProtectsNoHandles() {
        assertTrue(
            leftoverProtectedHandles(
                savedDevices = listOf(saved("AA:BB:CC:DD:EE:FF")),
                connectionState = DeviceConnectionState.DISCONNECTED,
                lastConnectedId = Uuid.normalized("11111111-1111-1111-1111-111111111111"),
            ).isEmpty(),
        )
    }

    @Test
    fun aLinkedSessionProtectsTheLastRadioHandle() {
        val lastId = Uuid.normalized("11111111-1111-1111-1111-111111111111")
        assertEquals(
            listOf("AA:BB:CC:DD:EE:FF"),
            leftoverProtectedHandles(
                savedDevices = listOf(saved("AA:BB:CC:DD:EE:FF")),
                connectionState = DeviceConnectionState.READY,
                lastConnectedId = lastId,
            ),
        )
    }

    @Test
    fun factoryResetClearIsDisabledWhenThereAreNoPairings() {
        assertFalse(troubleshootingFactoryResetEnabled(0))
        assertTrue(troubleshootingFactoryResetEnabled(1))
    }

    @Test
    fun factoryResetClearDisassociatesEveryNonBlankHandle() {
        val forgotten = mutableListOf<String>()
        clearStaleCompanionPairings(listOf(radio, other, CompanionAssociation(handle = "  ", name = "Ghost"))) {
            forgotten += it
        }
        assertEquals(listOf("AA:BB:CC:DD:EE:FF", "11:22:33:44:55:66"), forgotten)
    }

    @Test
    fun factoryResetClearWithNoAssociationsIsANoOp() {
        val forgotten = mutableListOf<String>()
        clearStaleCompanionPairings(emptyList()) { forgotten += it }
        assertTrue(forgotten.isEmpty())
    }

    @Test
    fun settingsUnpairReportsHandlesMissingFromTheNewSnapshot() {
        val removed = companionAssociationRemovals(
            previous = listOf(radio, other),
            current = listOf(other),
        )
        assertEquals(listOf(radio), removed)
    }

    @Test
    fun settingsUnpairIgnoresHandleLetterCase() {
        val removed = companionAssociationRemovals(
            previous = listOf(radio),
            current = listOf(CompanionAssociation(handle = "aa:bb:cc:dd:ee:ff", name = "Kitchen")),
        )
        assertTrue(removed.isEmpty())
    }

    @Test
    fun aSavedRowMatchesTheCompanionHandleIgnoringCase() {
        val devices = listOf(saved("AA:BB:CC:DD:EE:FF"))
        assertEquals(devices.single(), savedDeviceMatchingCompanionHandle(devices, "aa:bb:cc:dd:ee:ff"))
        assertEquals(null, savedDeviceMatchingCompanionHandle(devices, "11:22:33:44:55:66"))
    }

    @Test
    fun chooserCancelDoesNotFallBackToInAppScan() {
        assertFalse(shouldFallBackToInAppBleScan(CompanionAssociateResult.Cancelled))
        assertTrue(shouldFallBackToInAppBleScan(CompanionAssociateResult.Unavailable))
        assertTrue(shouldFallBackToInAppBleScan(CompanionAssociateResult.Failed("denied")))
        assertFalse(
            shouldFallBackToInAppBleScan(
                CompanionAssociateResult.Associated(radio),
            ),
        )
    }

    @Test
    fun scanDisconnectsALiveRadioBeforeCompanionPairing() = runTest {
        var disconnected = 0
        disconnectLiveRadioBeforeCompanionPairing(isRadioLinked = true, disconnect = { disconnected += 1 })
        assertEquals(1, disconnected)
        disconnectLiveRadioBeforeCompanionPairing(
            isRadioLinked = false,
            disconnect = { error("disconnect must not run") },
        )
    }

    @Test
    fun leftoverConfirmationKeepsTheLiveRadio() = runTest {
        var disconnected = 0
        var noted = 0
        assertFalse(
            prepareCompanionChooser(
                isRadioLinked = true,
                hasLeftovers = true,
                disconnect = { disconnected += 1 },
                noteWantsConnection = { noted += 1 },
            ),
        )
        assertEquals(0, disconnected)
        assertEquals(0, noted)
    }

    @Test
    fun chooserStampsWantsConnectionAfterDisconnect() = runTest {
        var disconnected = 0
        var noted = 0
        assertTrue(
            prepareCompanionChooser(
                isRadioLinked = true,
                hasLeftovers = false,
                disconnect = { disconnected += 1 },
                noteWantsConnection = { noted += 1 },
            ),
        )
        assertEquals(1, disconnected)
        assertEquals(1, noted)
    }

    @Test
    fun chooserStampsWantsConnectionWhenAlreadyDisconnected() = runTest {
        var noted = 0
        assertTrue(
            prepareCompanionChooser(
                isRadioLinked = false,
                hasLeftovers = false,
                disconnect = { error("disconnect must not run") },
                noteWantsConnection = { noted += 1 },
            ),
        )
        assertEquals(1, noted)
    }

    @Test
    fun leftoverForgetRetriesChooserFailureOnce() {
        assertTrue(shouldRetryCompanionAssociateAfterLeftoverForget(CompanionAssociateResult.Failed("restricted")))
        assertTrue(shouldRetryCompanionAssociateAfterLeftoverForget(CompanionAssociateResult.Unavailable))
        assertFalse(shouldRetryCompanionAssociateAfterLeftoverForget(CompanionAssociateResult.Cancelled))
        assertFalse(
            shouldRetryCompanionAssociateAfterLeftoverForget(CompanionAssociateResult.Associated(radio)),
        )
    }

    @Test
    fun leftoverForgetRetryAssociatesTwiceOnFirstFailure() = runTest {
        var calls = 0
        val result = associateAfterLeftoverForget(
            associate = {
                calls += 1
                if (calls == 1) {
                    CompanionAssociateResult.Failed("restricted")
                } else {
                    CompanionAssociateResult.Associated(radio)
                }
            },
            delayMs = {},
        )
        assertEquals(CompanionAssociateResult.Associated(radio), result)
        assertEquals(2, calls)
    }

    @Test
    fun leftoverForgetDoesNotRetryChooserCancel() = runTest {
        var calls = 0
        val result = associateAfterLeftoverForget(
            associate = {
                calls += 1
                CompanionAssociateResult.Cancelled
            },
            delayMs = {},
        )
        assertEquals(CompanionAssociateResult.Cancelled, result)
        assertEquals(1, calls)
    }

    @Test
    fun leftoverForgetRetryWaitsThePickerRestrictedDelay() = runTest {
        var delayedMs = 0L
        associateAfterLeftoverForget(
            associate = { CompanionAssociateResult.Failed("restricted") },
            delayMs = { delayedMs = it },
        )
        assertEquals(FRESH_COMPANION_PAIRING_RETRY_DELAY_MS, delayedMs)
    }

    @Test
    fun leftoverForgetDisconnectsALiveSessionAndSkipsLeftoverRecheck() = runTest {
        var disconnected = 0
        var associates = 0
        val result = completeFreshCompanionAssociate(
            FreshCompanionAssociate(
                isRadioLinked = true,
                disconnect = { disconnected += 1 },
                isAvailable = true,
                associate = {
                    associates += 1
                    CompanionAssociateResult.Associated(radio)
                },
                delayMs = {},
            ),
        )
        assertEquals(CompanionAssociateResult.Associated(radio), result)
        assertEquals(1, disconnected)
        assertEquals(1, associates)
    }

    @Test
    fun leftoverForgetFallsBackWhenCompanionManagerIsMissing() = runTest {
        var associates = 0
        val result = completeFreshCompanionAssociate(
            FreshCompanionAssociate(
                isRadioLinked = false,
                disconnect = { error("disconnect must not run") },
                isAvailable = false,
                associate = {
                    associates += 1
                    CompanionAssociateResult.Associated(radio)
                },
                delayMs = {},
            ),
        )
        assertEquals(CompanionAssociateResult.Unavailable, result)
        assertEquals(0, associates)
        assertTrue(shouldFallBackToInAppBleScan(result))
    }

    @Test
    fun secondChooserFailureAfterLeftoverForgetFallsBackToScan() = runTest {
        var calls = 0
        val result = completeFreshCompanionAssociate(
            FreshCompanionAssociate(
                isRadioLinked = false,
                disconnect = {},
                isAvailable = true,
                associate = {
                    calls += 1
                    CompanionAssociateResult.Failed("restricted")
                },
                delayMs = {},
            ),
        )
        assertEquals(CompanionAssociateResult.Failed("restricted"), result)
        assertEquals(2, calls)
        assertTrue(shouldFallBackToInAppBleScan(result))
    }

    @Test
    fun removeBondIsPublicFromApi36() {
        assertFalse(companionDeviceManagerCanRemoveBond(35))
        assertTrue(companionDeviceManagerCanRemoveBond(36))
        assertEquals(36, COMPANION_REMOVE_BOND_MIN_SDK)
    }

    @Test
    fun presenceObservationIsPublicFromApi31() {
        assertFalse(companionDeviceManagerCanObservePresence(30))
        assertTrue(companionDeviceManagerCanObservePresence(31))
        assertEquals(31, COMPANION_PRESENCE_MIN_SDK)
    }

    @Test
    fun presenceObservesOnlyTheLastWantedAssociatedHandle() {
        assertEquals(
            listOf("AA:BB:CC:DD:EE:FF"),
            companionPresenceHandlesToObserve(
                associations = listOf(radio, other),
                lastWantedBleHandle = "aa:bb:cc:dd:ee:ff",
                userDisconnected = false,
            ),
        )
    }

    @Test
    fun presenceObservesNothingWithoutAWantedHandle() {
        assertTrue(
            companionPresenceHandlesToObserve(
                associations = listOf(radio, other),
                lastWantedBleHandle = null,
                userDisconnected = false,
            ).isEmpty(),
        )
        assertTrue(
            companionPresenceHandlesToObserve(
                associations = listOf(radio, other),
                lastWantedBleHandle = "  ",
                userDisconnected = false,
            ).isEmpty(),
        )
    }

    @Test
    fun presenceObservesNothingAfterExplicitDisconnect() {
        assertTrue(
            companionPresenceHandlesToObserve(
                associations = listOf(radio),
                lastWantedBleHandle = radio.handle,
                userDisconnected = true,
            ).isEmpty(),
        )
    }

    @Test
    fun leftoverAssociationIsNotObservedWhenItIsNotLastWanted() {
        assertTrue(
            companionPresenceHandlesToObserve(
                associations = listOf(other),
                lastWantedBleHandle = radio.handle,
                userDisconnected = false,
            ).isEmpty(),
        )
    }

    @Test
    fun presenceWakeRequiresTheLastWantedHandle() {
        assertTrue(
            shouldStartConnectionOnRadioPresence(
                appearedHandle = "aa:bb:cc:dd:ee:ff",
                lastWantedBleHandle = radio.handle,
                userDisconnected = false,
            ),
        )
        assertFalse(
            shouldStartConnectionOnRadioPresence(
                appearedHandle = other.handle,
                lastWantedBleHandle = radio.handle,
                userDisconnected = false,
            ),
        )
        assertFalse(
            shouldStartConnectionOnRadioPresence(
                appearedHandle = radio.handle,
                lastWantedBleHandle = radio.handle,
                userDisconnected = true,
            ),
        )
        assertFalse(
            shouldStartConnectionOnRadioPresence(
                appearedHandle = null,
                lastWantedBleHandle = radio.handle,
                userDisconnected = false,
            ),
        )
    }

    private fun saved(handle: String) = SavedRadioDevice(
        id = Uuid.normalized("11111111-1111-1111-1111-111111111111"),
        name = "Radio",
        wifi = null,
        bleHandle = handle,
        lastConnected = 0.0,
    )
}
