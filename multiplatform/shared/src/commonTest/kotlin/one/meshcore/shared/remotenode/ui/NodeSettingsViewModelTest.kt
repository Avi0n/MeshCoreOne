package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodeSettingsValidation
import one.meshcore.shared.remotenode.RemoteCLICommandRewriter
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.RepeaterRegionEntry
import one.meshcore.shared.remotenode.answerCli
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.remotenode.testOwnerInfoResponse
import one.meshcore.shared.remotenode.testSession
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Disconnected no-ops and collapsed sections that must not radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsViewModelTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    /** A section that never loaded shows as unloaded; it does not need a banner over the whole screen. */
    @Test
    fun loadMethodsAreSilentNoOpsWhenAdminServiceMissing() = runBlocking {
        val vm = disconnectedViewModel(scope)

        vm.loadDeviceInfo()
        vm.loadIdentity()
        vm.loadRadio()
        vm.loadContactInfo()
        vm.loadBehavior()
        vm.loadRegions()

        val state = vm.state.value
        assertFalse(state.isLoadingDeviceInfo)
        assertFalse(state.isLoadingIdentity)
        assertFalse(state.isLoadingRadio)
        assertFalse(state.isLoadingContactInfo)
        assertFalse(state.isLoadingBehavior)
        assertFalse(state.isLoadingRegions)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun loadOpenSectionsIsASilentNoOpWhenDisconnected() = runBlocking {
        val vm = disconnectedViewModel(scope)
        vm.loadOpenSections()
        val state = vm.state.value
        assertFalse(state.isLoadingDeviceInfo)
        assertFalse(state.deviceInfoLoaded)
        assertFalse(state.isLoadingRadio)
        assertFalse(state.radioLoaded)
        assertFalse(state.isLoadingRegions)
        assertFalse(state.regionsLoaded)
    }

    /**
     * An action the user asked for and that never happened has to say so. Silence reads as success, which
     * on a repeater config is the worst reading available.
     */
    @Test
    fun everyActionReportsAMissingAdminService() = runBlocking {
        val actions = listOf<Pair<String, (NodeSettingsViewModel) -> Unit>>(
            "applyIdentity" to { it.applyIdentity() },
            "applyRadio" to { it.applyRadio() },
            "applyContactInfo" to { it.applyContactInfo() },
            "applyBehavior" to { it.applyBehavior() },
            "saveRegions" to { it.saveRegions() },
            "addRegion" to { it.addRegion() },
            "removeRegion" to { it.removeRegion("a") },
            "toggleRegionFlood" to { it.toggleRegionFlood("a") },
            "setDefaultScope" to { it.setDefaultScope("a") },
            "changePassword" to { it.changePassword() },
            "confirmReboot" to { it.confirmReboot() },
            "sendAdvert" to { it.sendAdvert() },
            "syncTime" to { it.syncTime() },
        )

        for ((name, action) in actions) {
            val vm = disconnectedViewModel(scope)
            action(vm)
            assertEquals(ApplyBanner.NoService, vm.state.value.applyBanner, "$name stayed silent")
        }
    }

    /** No action may look as though it is still running once it has been refused. */
    @Test
    fun aRefusedActionLeavesNothingSpinning() = runBlocking {
        val vm = disconnectedViewModel(scope)

        vm.applyRadio()
        vm.sendAdvert()
        vm.syncTime()

        val state = vm.state.value
        assertFalse(state.isSendingAdvert)
        assertFalse(state.isSyncingTime)
        assertFalse(state.isApplyingIdentity)
        assertFalse(state.isApplyingRadio)
        assertFalse(state.isApplyingContactInfo)
        assertFalse(state.isApplyingBehavior)
        assertFalse(state.isSavingRegions)
    }

    @Test
    fun setLocationFromPickerFillsCoordinateFieldsAndMarksModified() = runBlocking {
        // The map picker is reachable only inside an open settings sheet, where the admin service (and thus
        // the coordinate-holding controller) is live; a live fake mirrors that reachability.
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val admin = RepeaterAdminService(
            RemoteNodeService(session, store, passwordStore, scope),
            NodeCliCoordinator(session, store, passwordStore, scope),
            session,
            store,
            passwordStore,
        )
        val vm = NodeSettingsViewModel(
            scope = scope,
            sessionId = sessionId,
            dependencies = NodeSettingsViewModel.Dependencies(repeaterAdminService = { admin }),
        )

        vm.setLocationFromPicker(latitude = 12.5, longitude = -34.25)

        val state = vm.state.value
        assertEquals("12.5", state.latitudeText)
        assertEquals("-34.25", state.longitudeText)
        assertTrue(state.identityModified)
    }

    @Test
    fun expandingSectionSetsFlagWithoutLoadingWhenDisconnected() = runBlocking {
        val vm = NodeSettingsViewModel(
            scope = scope,
            sessionId = sessionId,
            dependencies = NodeSettingsViewModel.Dependencies(repeaterAdminService = { null }),
        )

        vm.setDeviceInfoExpanded(true)
        vm.setIdentityExpanded(true)
        vm.setRadioExpanded(true)
        vm.setContactInfoExpanded(true)
        vm.setBehaviorExpanded(true)
        vm.setRegionsExpanded(true)

        val state = vm.state.value
        assertTrue(state.deviceInfoExpanded)
        assertTrue(state.identityExpanded)
        assertTrue(state.radioExpanded)
        assertTrue(state.contactInfoExpanded)
        assertTrue(state.behaviorExpanded)
        assertTrue(state.regionsExpanded)
        assertFalse(state.isLoadingDeviceInfo)
        assertFalse(state.deviceInfoLoaded)
        assertFalse(state.isLoadingBehavior)
        assertFalse(state.behaviorLoaded)
        assertFalse(state.isLoadingRegions)
        assertFalse(state.regionsLoaded)
    }

    @Test
    fun openingSecuritySettingsDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertFalse(vm.state.value.securityExpanded)
        val traffic = h.nodeSession.radioTraffic()

        vm.setSecurityExpanded(true)
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        assertTrue(vm.state.value.securityExpanded)
        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.isChangingPassword)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun keepingTheRepeaterRebootConfirmHiddenDoesNotRadio() = runTest {
        val quiet = quietRepeaterSettings()
        val vm = quiet.vm
        assertFalse(vm.state.value.showRebootConfirm)
        assertFalse(vm.state.value.deviceActionsExpanded)
        assertFalse(vm.state.value.isRebooting)
        assertFalse(vm.state.value.isSyncingTime)
        assertEquals(null, vm.state.value.applyBanner)

        vm.setShowRebootConfirm(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.showRebootConfirm)
        assertFalse(vm.state.value.deviceActionsExpanded)
        assertFalse(vm.state.value.isRebooting)
        assertFalse(vm.state.value.isSyncingTime)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun keepingRepeaterRadioSettingsCollapsedDoesNotRadio() = runTest {
        val quiet = quietRepeaterSettings()
        val vm = quiet.vm
        assertFalse(vm.state.value.radioExpanded)
        assertFalse(vm.state.value.radioLoaded)
        assertFalse(vm.state.value.isLoadingRadio)
        assertFalse(vm.state.value.radioSectionError)
        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
        assertEquals("", vm.state.value.spreadingFactorText)
        assertEquals("", vm.state.value.codingRateText)
        assertFalse(vm.state.value.radioModified)
        assertFalse(vm.state.value.isApplyingRadio)
        assertEquals(null, vm.state.value.applyBanner)

        vm.setRadioExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.radioExpanded)
        assertFalse(vm.state.value.radioLoaded)
        assertFalse(vm.state.value.isLoadingRadio)
        assertFalse(vm.state.value.radioSectionError)
        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
        assertEquals("", vm.state.value.spreadingFactorText)
        assertEquals("", vm.state.value.codingRateText)
        assertFalse(vm.state.value.radioModified)
        assertFalse(vm.state.value.isApplyingRadio)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun keepingAnEmptyRepeaterRegionNameDoesNotRadio() = runTest {
        val quiet = quietRepeaterSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.newRegionName)
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)
        assertFalse(vm.state.value.regionsExpanded)
        assertFalse(vm.state.value.regionsLoaded)
        assertFalse(vm.state.value.isLoadingRegions)
        assertTrue(vm.state.value.regions.isEmpty())
        assertEquals(null, vm.state.value.applyBanner)

        vm.setNewRegionName("")
        runCurrent()

        quiet.assertStillQuiet()
        assertEquals("", vm.state.value.newRegionName)
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)
        assertFalse(vm.state.value.regionsExpanded)
        assertFalse(vm.state.value.regionsLoaded)
        assertFalse(vm.state.value.isLoadingRegions)
        assertTrue(vm.state.value.regions.isEmpty())
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun keepingRepeaterIdentityCollapsedDoesNotRadio() = runTest {
        val quiet = quietRepeaterSettings()
        val vm = quiet.vm
        assertFalse(vm.state.value.identityExpanded)
        assertFalse(vm.state.value.identityLoaded)
        assertFalse(vm.state.value.isLoadingIdentity)
        assertFalse(vm.state.value.identitySectionError)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertFalse(vm.state.value.identityModified)
        assertEquals(null, vm.state.value.applyBanner)

        vm.setIdentityExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.identityExpanded)
        assertFalse(vm.state.value.identityLoaded)
        assertFalse(vm.state.value.isLoadingIdentity)
        assertFalse(vm.state.value.identitySectionError)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertFalse(vm.state.value.identityModified)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun keepingRepeaterBehaviorCollapsedDoesNotRadio() = runTest {
        val quiet = quietRepeaterSettings()
        val vm = quiet.vm
        assertFalse(vm.state.value.behaviorExpanded)
        assertFalse(vm.state.value.behaviorLoaded)
        assertFalse(vm.state.value.isLoadingBehavior)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.repeaterEnabled)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertEquals(null, vm.state.value.applyBanner)

        vm.setBehaviorExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.behaviorExpanded)
        assertFalse(vm.state.value.behaviorLoaded)
        assertFalse(vm.state.value.isLoadingBehavior)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.repeaterEnabled)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun keepingRepeaterDeviceInfoCollapsedDoesNotRadio() = runTest {
        val quiet = quietRepeaterSettings()
        val vm = quiet.vm
        assertFalse(vm.state.value.deviceInfoExpanded)
        assertFalse(vm.state.value.deviceInfoLoaded)
        assertFalse(vm.state.value.isLoadingDeviceInfo)
        assertFalse(vm.state.value.deviceInfoSectionError)
        assertEquals(null, vm.state.value.firmwareVersion)
        assertEquals(null, vm.state.value.applyBanner)

        vm.setDeviceInfoExpanded(false)
        runCurrent()

        quiet.assertStillQuiet()
        assertFalse(vm.state.value.deviceInfoExpanded)
        assertFalse(vm.state.value.deviceInfoLoaded)
        assertFalse(vm.state.value.isLoadingDeviceInfo)
        assertFalse(vm.state.value.deviceInfoSectionError)
        assertEquals(null, vm.state.value.firmwareVersion)
        assertEquals(null, vm.state.value.applyBanner)
    }
}

/**
 * Typing repeater fields while the section is open must not radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsTypingSilenceTest {
    @Test
    fun openingRepeaterDeviceActionsDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertFalse(vm.state.value.deviceActionsExpanded)
        val traffic = h.nodeSession.radioTraffic()

        vm.setDeviceActionsExpanded(true)
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertTrue(state.deviceActionsExpanded)
        assertFalse(state.showRebootConfirm)
        assertFalse(state.isSendingAdvert)
        assertFalse(state.isRebooting)
        assertFalse(state.isSyncingTime)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterLatitudeDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        val longitudeBefore = vm.state.value.longitudeText
        val traffic = h.nodeSession.radioTraffic()

        vm.setLatitudeText("41.25")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("41.25", state.latitudeText)
        assertEquals(longitudeBefore, state.longitudeText)
        assertTrue(state.identityModified)
        assertEquals(null, state.identityValidation)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterLongitudeDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.longitudeText)
        assertFalse(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityExpanded)
        val latitudeBefore = vm.state.value.latitudeText
        val nameBefore = vm.state.value.name
        val identityLoadedBefore = vm.state.value.identityLoaded
        val traffic = h.nodeSession.radioTraffic()

        vm.setLongitudeText("-75.1")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("-75.1", state.longitudeText)
        assertEquals(latitudeBefore, state.latitudeText)
        assertEquals(nameBefore, state.name)
        assertTrue(state.identityModified)
        assertEquals(null, state.identityValidation)
        assertFalse(state.identityExpanded)
        assertEquals(identityLoadedBefore, state.identityLoaded)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterFrequencyDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        val bandwidthBefore = vm.state.value.bandwidthText
        assertFalse(vm.state.value.radioModified)
        val traffic = h.nodeSession.radioTraffic()

        vm.setFrequencyText("910.0")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("910.0", state.frequencyText)
        assertEquals(bandwidthBefore, state.bandwidthText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun turningARepeaterOffDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertFalse(vm.state.value.repeaterEnabled)
        assertFalse(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorExpanded)
        val advertBefore = vm.state.value.advertIntervalText
        val traffic = h.nodeSession.radioTraffic()

        vm.setRepeaterEnabled(false)
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertFalse(state.repeaterEnabled)
        assertEquals(advertBefore, state.advertIntervalText)
        assertTrue(state.behaviorModified)
        assertFalse(state.behaviorExpanded)
        assertFalse(state.behaviorLoaded)
        assertFalse(state.isApplyingBehavior)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterFloodHopLimitDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertFalse(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorExpanded)
        val advertBefore = vm.state.value.advertIntervalText
        val traffic = h.nodeSession.radioTraffic()

        vm.setFloodMaxHopsText("4")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("4", state.floodMaxHopsText)
        assertEquals("", state.floodAdvertIntervalText)
        assertEquals(advertBefore, state.advertIntervalText)
        assertFalse(state.repeaterEnabled)
        assertTrue(state.behaviorModified)
        assertFalse(state.behaviorExpanded)
        assertFalse(state.behaviorLoaded)
        assertFalse(state.isApplyingBehavior)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterAdvertIntervalDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorExpanded)
        assertFalse(vm.state.value.repeaterEnabled)
        val traffic = h.nodeSession.radioTraffic()

        vm.setAdvertIntervalText("90")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("90", state.advertIntervalText)
        assertEquals("", state.floodAdvertIntervalText)
        assertEquals("", state.floodMaxHopsText)
        assertFalse(state.repeaterEnabled)
        assertTrue(state.behaviorModified)
        assertFalse(state.behaviorExpanded)
        assertFalse(state.behaviorLoaded)
        assertEquals(null, state.behaviorValidation)
        assertFalse(state.isApplyingBehavior)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingRepeaterOwnerInfoDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        vm.setContactInfoExpanded(true)
        runCurrent()
        vm.setContactInfoExpanded(false)
        assertEquals("owner", vm.state.value.ownerInfo)
        assertFalse(vm.state.value.ownerInfoTooLong)
        assertFalse(vm.state.value.contactInfoModified)
        assertFalse(vm.state.value.contactInfoExpanded)
        assertTrue(vm.state.value.contactInfoLoaded)
        val nameBefore = vm.state.value.name
        val traffic = h.nodeSession.radioTraffic()

        vm.setOwnerInfo("shed")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("shed", state.ownerInfo)
        assertEquals(nameBefore, state.name)
        assertFalse(state.ownerInfoTooLong)
        assertTrue(state.contactInfoModified)
        assertFalse(state.contactInfoExpanded)
        assertTrue(state.contactInfoLoaded)
        assertFalse(state.isLoadingContactInfo)
        assertFalse(state.isApplyingContactInfo)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterPasswordDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.securityExpanded)
        assertFalse(vm.state.value.isChangingPassword)
        val traffic = h.nodeSession.radioTraffic()

        vm.setNewPassword("harbor9")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("harbor9", state.newPassword)
        assertEquals("", state.confirmPassword)
        assertFalse(state.securityExpanded)
        assertFalse(state.isChangingPassword)
        assertEquals(null, state.applyBanner)
    }
}

/**
 * Choosing a repeater option before the section loads must not radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsChoiceSilenceTest {
    @Test
    fun choosingARepeaterBandwidthDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.bandwidthText)
        assertFalse(vm.state.value.radioModified)
        assertFalse(vm.state.value.radioExpanded)
        val frequencyBefore = vm.state.value.frequencyText
        val spreadingBefore = vm.state.value.spreadingFactorText
        val codingBefore = vm.state.value.codingRateText
        val radioLoadedBefore = vm.state.value.radioLoaded
        val traffic = h.nodeSession.radioTraffic()

        vm.setBandwidth(62.5)
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("62.5", state.bandwidthText)
        assertEquals(frequencyBefore, state.frequencyText)
        assertEquals(spreadingBefore, state.spreadingFactorText)
        assertEquals(codingBefore, state.codingRateText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertEquals(radioLoadedBefore, state.radioLoaded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterNameDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        vm.setContactInfoExpanded(true)
        runCurrent()
        assertEquals("Test Node", vm.state.value.name)
        assertFalse(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityExpanded)
        val latitudeBefore = vm.state.value.latitudeText
        val longitudeBefore = vm.state.value.longitudeText
        val identityLoadedBefore = vm.state.value.identityLoaded
        val traffic = h.nodeSession.radioTraffic()

        vm.setName("Ridge")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("Ridge", state.name)
        assertEquals(latitudeBefore, state.latitudeText)
        assertEquals(longitudeBefore, state.longitudeText)
        assertTrue(state.identityModified)
        assertEquals(null, state.identityValidation)
        assertFalse(state.identityExpanded)
        assertEquals(identityLoadedBefore, state.identityLoaded)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterPasswordConfirmationDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.securityExpanded)
        assertFalse(vm.state.value.isChangingPassword)
        val traffic = h.nodeSession.radioTraffic()

        vm.setConfirmPassword("harbor9")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("", state.newPassword)
        assertEquals("harbor9", state.confirmPassword)
        assertFalse(state.securityExpanded)
        assertFalse(state.isChangingPassword)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARepeaterFloodAdvertIntervalDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorExpanded)
        assertFalse(vm.state.value.repeaterEnabled)
        val traffic = h.nodeSession.radioTraffic()

        vm.setFloodAdvertIntervalText("6")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("6", state.floodAdvertIntervalText)
        assertEquals("", state.advertIntervalText)
        assertEquals("", state.floodMaxHopsText)
        assertFalse(state.repeaterEnabled)
        assertTrue(state.behaviorModified)
        assertFalse(state.behaviorExpanded)
        assertFalse(state.behaviorLoaded)
        assertEquals(null, state.behaviorValidation)
        assertFalse(state.isApplyingBehavior)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingARepeaterSpreadingFactorDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.spreadingFactorText)
        assertFalse(vm.state.value.radioModified)
        assertFalse(vm.state.value.radioExpanded)
        val frequencyBefore = vm.state.value.frequencyText
        val bandwidthBefore = vm.state.value.bandwidthText
        val codingBefore = vm.state.value.codingRateText
        val radioLoadedBefore = vm.state.value.radioLoaded
        val traffic = h.nodeSession.radioTraffic()

        vm.setSpreadingFactor(9)
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("9", state.spreadingFactorText)
        assertEquals(frequencyBefore, state.frequencyText)
        assertEquals(bandwidthBefore, state.bandwidthText)
        assertEquals(codingBefore, state.codingRateText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertEquals(radioLoadedBefore, state.radioLoaded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingAnUnloadedRepeaterRegionParentDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)
        assertFalse(vm.state.value.regionsExpanded)
        assertFalse(vm.state.value.regionsLoaded)
        assertFalse(vm.state.value.isLoadingRegions)
        assertFalse(vm.state.value.isSavingRegions)
        val traffic = h.nodeSession.radioTraffic()

        vm.setNewRegionParent(RepeaterRegionEntry.Parent.Named("duckburg"))
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, state.newRegionParent)
        assertEquals("", state.newRegionName)
        assertFalse(state.regionsExpanded)
        assertFalse(state.regionsLoaded)
        assertFalse(state.isLoadingRegions)
        assertFalse(state.isSavingRegions)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingARepeaterCodingRateDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertEquals("", vm.state.value.codingRateText)
        assertFalse(vm.state.value.radioModified)
        assertFalse(vm.state.value.radioExpanded)
        val frequencyBefore = vm.state.value.frequencyText
        val bandwidthBefore = vm.state.value.bandwidthText
        val spreadingBefore = vm.state.value.spreadingFactorText
        val radioLoadedBefore = vm.state.value.radioLoaded
        val traffic = h.nodeSession.radioTraffic()

        vm.setCodingRate(5)
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("5", state.codingRateText)
        assertEquals(frequencyBefore, state.frequencyText)
        assertEquals(bandwidthBefore, state.bandwidthText)
        assertEquals(spreadingBefore, state.spreadingFactorText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertEquals(radioLoadedBefore, state.radioLoaded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingARepeaterMapPointDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        assertFalse(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityExpanded)
        val nameBefore = vm.state.value.name
        val identityLoadedBefore = vm.state.value.identityLoaded
        val traffic = h.nodeSession.radioTraffic()

        vm.setLocationFromPicker(latitude = 12.5, longitude = -34.25)
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        val state = vm.state.value
        assertEquals("12.5", state.latitudeText)
        assertEquals("-34.25", state.longitudeText)
        assertEquals(nameBefore, state.name)
        assertTrue(state.identityModified)
        assertEquals(null, state.identityValidation)
        assertFalse(state.identityExpanded)
        assertEquals(identityLoadedBefore, state.identityLoaded)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }
}

/**
 * Advert, clock, and late radio replies publish through the shared admin session.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsAdvertClockTest {
    @Test
    fun sendAdvertRoutesThroughControllerAndBanners() = runTest {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        val admin = RepeaterAdminService(
            RemoteNodeService(nodeSession, store, pw, backgroundScope),
            NodeCliCoordinator(nodeSession, store, pw, backgroundScope),
            nodeSession,
            store,
            pw,
        )
        val vm = NodeSettingsViewModel(backgroundScope, dto.id, NodeSettingsViewModel.Dependencies { admin })
        runCurrent()

        vm.sendAdvert()
        runCurrent()
        assertTrue(vm.state.value.isSendingAdvert)
        assertTrue(nodeSession.sendCommandCalls.single().second.endsWith("advert"))

        nodeSession.emitCliResponse("00|OK")
        runCurrent()
        assertEquals(ApplyBanner.AdvertSent, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSendingAdvert)
    }

    @Test
    fun deniedAdvertStillShowsTheSentNotice() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.sendAdvert()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("advert"))
        val prefix = command.removeSuffix("advert")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.AdvertSent, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSendingAdvert)
    }

    @Test
    fun syncTimeRoutesThroughControllerAndBanners() = runTest {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        val admin = RepeaterAdminService(
            RemoteNodeService(nodeSession, store, pw, backgroundScope),
            NodeCliCoordinator(nodeSession, store, pw, backgroundScope),
            nodeSession,
            store,
            pw,
        )
        val vm = NodeSettingsViewModel(backgroundScope, dto.id, NodeSettingsViewModel.Dependencies { admin })
        runCurrent()

        vm.syncTime()
        runCurrent()
        assertTrue(vm.state.value.isSyncingTime)
        val sent = nodeSession.sendCommandCalls.single().second
        assertTrue(sent.contains(RemoteCLICommandRewriter.TIME_COMMAND_PREFIX))
        assertFalse(sent.endsWith("clock sync"))

        nodeSession.emitCliResponse("00|OK")
        runCurrent()
        assertEquals(ApplyBanner.TimeSynced, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSyncingTime)
    }

    @Test
    fun clockAheadDoesNotPublishSynced() = runTest {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        val admin = RepeaterAdminService(
            RemoteNodeService(nodeSession, store, pw, backgroundScope),
            NodeCliCoordinator(nodeSession, store, pw, backgroundScope),
            nodeSession,
            store,
            pw,
        )
        val vm = NodeSettingsViewModel(backgroundScope, dto.id, NodeSettingsViewModel.Dependencies { admin })
        runCurrent()

        vm.syncTime()
        runCurrent()
        assertTrue(vm.state.value.isSyncingTime)
        val sent = nodeSession.sendCommandCalls.single().second
        assertTrue(sent.contains(RemoteCLICommandRewriter.TIME_COMMAND_PREFIX))

        nodeSession.emitCliResponse("00|ERR: clock cannot go backwards")
        runCurrent()

        assertEquals(ApplyBanner.ClockAhead, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSyncingTime)
        assertEquals(null, vm.state.value.deviceTimeUtc)
        assertEquals(null, vm.state.value.deviceTimeEpochSeconds)
        assertEquals(null, vm.state.value.clockDriftSeconds)
    }

    @Test
    fun clockErrorShowsTheFirmwareText() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.syncTime()
        runCurrent()
        assertTrue(vm.state.value.isSyncingTime)
        val command = h.nodeSession.sendCommandCalls.last().second
        val marker = "time "
        assertTrue(command.contains(marker))
        assertFalse(command.contains("clock sync"))
        val epoch = command.substringAfterLast(marker)
        val prefix = command.removeSuffix(marker + epoch)
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.FirmwareError("denied"), vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSyncingTime)
        assertEquals(null, vm.state.value.deviceTimeUtc)
        assertEquals(null, vm.state.value.deviceTimeEpochSeconds)
        assertEquals(null, vm.state.value.clockDriftSeconds)
    }

    @Test
    fun lateCliReplyFillsTheRadioSectionAndClearsItsError() = runTest {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwords = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(nodeSession, store, passwords, backgroundScope)
        val cli = NodeCliCoordinator(nodeSession, store, passwords, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, nodeSession, store, passwords)
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(session)
        store.setContact(session.radioID, testContact(publicKey = session.publicKey))
        // Flood-routed, so the CLI timeout is terminal rather than triggering path recovery.
        store.setContactRouting(session.radioID, session.publicKey, isFloodRouted = true)
        val vm = NodeSettingsViewModel(
            scope = backgroundScope,
            sessionId = session.id,
            dependencies = NodeSettingsViewModel.Dependencies(repeaterAdminService = { admin }),
        )
        runCurrent()

        // The radio query goes unanswered, so the section reports its error with no values.
        vm.loadRadio()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.radioSectionError)
        assertEquals("", vm.state.value.frequencyText)

        // The reply lands after the section gave up; it must still fill the field.
        nodeSession.emitCliResponse("915.0,250.0,10,5", session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.radioSectionError)
        assertEquals("915.0", vm.state.value.frequencyText)
    }

    @Test
    fun lateRadioReplyPublishesEveryFieldAndSnapsBandwidth() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRadio()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.radioSectionError)

        h.nodeSession.emitCliResponse("915.0,7.799,10,5", h.session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.radioSectionError)
        assertEquals("915.0", vm.state.value.frequencyText)
        assertEquals("7.8", vm.state.value.bandwidthText)
        assertEquals("10", vm.state.value.spreadingFactorText)
        assertEquals("5", vm.state.value.codingRateText)
        assertFalse(vm.state.value.radioModified)
    }

    @Test
    fun nonRadioReplyLeavesTheRadioFieldsEmpty() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRadio()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.radioSectionError)

        // A reply that is not radio settings cannot fill the timed-out radio query.
        h.nodeSession.emitCliResponse("not radio", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
        assertEquals("", vm.state.value.spreadingFactorText)
        assertEquals("", vm.state.value.codingRateText)
        assertTrue(vm.state.value.radioSectionError)
    }

    @Test
    fun lateClockReplyPublishesDeviceTime() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()
        assertEquals("v1.11.0", vm.state.value.firmwareVersion)

        vm.loadDeviceInfo()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.deviceInfoSectionError)
        assertEquals(null, vm.state.value.deviceTimeUtc)

        h.nodeSession.emitCliResponse(DEVICE_CLOCK_REPLY, h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(DEVICE_CLOCK_REPLY, vm.state.value.deviceTimeUtc)
        assertEquals(DEVICE_CLOCK_EPOCH_SECONDS, vm.state.value.deviceTimeEpochSeconds)
        // Firmware already arrived from the owner-info prefetch, so the clock reply clears the section.
        assertFalse(vm.state.value.deviceInfoSectionError)
    }

    @Test
    fun nonClockReplyLeavesTheDeviceTimeEmpty() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()
        assertEquals("v1.11.0", vm.state.value.firmwareVersion)

        vm.loadDeviceInfo()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.deviceInfoSectionError)
        assertEquals(null, vm.state.value.deviceTimeUtc)

        // A reply that is not a clock cannot fill the timed-out clock query.
        h.nodeSession.emitCliResponse("not a clock", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(null, vm.state.value.deviceTimeUtc)
        assertEquals("v1.11.0", vm.state.value.firmwareVersion)
        assertTrue(vm.state.value.deviceInfoSectionError)
    }

    @Test
    fun lateVersionReplyLeavesFirmwareEmptyWhenTheClockIsStored() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoError = IllegalStateException("offline")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()
        assertEquals(null, vm.state.value.firmwareVersion)

        vm.loadDeviceInfo()
        // ver times out. The clock query is the one still in flight.
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        h.nodeSession.emitCliResponse(DEVICE_CLOCK_REPLY, h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(null, vm.state.value.firmwareVersion)
        assertEquals(DEVICE_CLOCK_REPLY, vm.state.value.deviceTimeUtc)
        assertTrue(vm.state.value.deviceInfoLoaded)
        assertTrue(vm.state.value.deviceInfoSectionError)

        // ver is not machine-checked, so a later version reply cannot fill firmware.
        h.nodeSession.emitCliResponse("v1.2.3", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(null, vm.state.value.firmwareVersion)
        assertEquals(DEVICE_CLOCK_REPLY, vm.state.value.deviceTimeUtc)
        assertTrue(vm.state.value.deviceInfoLoaded)
        assertTrue(vm.state.value.deviceInfoSectionError)
    }

    @Test
    fun aCancelledOwnerInfoReadPropagatesAndLeavesFirmwareUnset() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoError = CancellationException("superseded")
        val model = NodeSettingsModel(
            scope = backgroundScope,
            sessionId = h.session.id,
            dependencies = NodeSettingsViewModel.Dependencies { h.admin },
            nowEpochSeconds = { 0 },
        )

        assertFailsWith<CancellationException> { model.fetchNodeInfo() }
        assertEquals(null, model.state.value.firmwareVersion)

        h.nodeSession.ownerInfoError = null
        h.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        model.fetchNodeInfo()
        assertEquals("v1.15.0", model.state.value.firmwareVersion)
    }

    @Test
    fun anOwnerInfoErrorPropagatesAndLeavesFirmwareUnset() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoError = NodeSettingsProbeError()
        val model = NodeSettingsModel(
            scope = backgroundScope,
            sessionId = h.session.id,
            dependencies = NodeSettingsViewModel.Dependencies { h.admin },
            nowEpochSeconds = { 0 },
        )

        assertFailsWith<NodeSettingsProbeError> { model.fetchNodeInfo() }
        assertEquals(null, model.state.value.firmwareVersion)
    }

    @Test
    fun ambiguousLateCoordinateDoesNotPublishEitherField() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadIdentity()
        // Name is already prefetched, so the section still waits out latitude and then longitude.
        advanceTimeBy(CLI_TIMEOUT_STEP * 2)
        runCurrent()
        assertTrue(vm.state.value.identitySectionError)

        // Both coordinates timed out, so a bare number belongs to neither field.
        h.nodeSession.emitCliResponse("38.5", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)
    }

    @Test
    fun lateLatitudePublishesWhenOnlyThatCoordinateIsUnanswered() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadIdentity()
        // Name is already prefetched. One step times out latitude and leaves longitude in flight.
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        val lonCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(lonCommand.endsWith("get lon"))

        // Longitude gets a reply that is not a coordinate, so only latitude stays unanswered.
        val wirePrefix = lonCommand.removeSuffix("get lon")
        h.nodeSession.emitCliResponse("${wirePrefix}not-a-coordinate", h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        h.nodeSession.emitCliResponse("38.5", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("Test Node", vm.state.value.name)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun duplicateLatitudeReplyDoesNotPublishAsLongitude() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()
        assertEquals("Test Node", vm.state.value.name)

        vm.loadIdentity()
        runCurrent()
        val latCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(latCommand.endsWith("get lat"))
        val wirePrefix = latCommand.removeSuffix("get lat")
        h.nodeSession.emitCliResponse("${wirePrefix}38.5", h.session.publicKey.copyOf(6))
        runCurrent()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        // The latitude reply was already seen, so the same text cannot fill longitude.
        h.nodeSession.emitCliResponse("38.5", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("Test Node", vm.state.value.name)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun laterLongitudePublishesAfterARepeatedLatitudeIsIgnored() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        val latCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(latCommand.endsWith("get lat"))
        val wirePrefix = latCommand.removeSuffix("get lat")
        h.nodeSession.emitCliResponse("${wirePrefix}38.5", h.session.publicKey.copyOf(6))
        runCurrent()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        h.nodeSession.emitCliResponse("38.5", h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        // The repeated latitude was ignored, so longitude is still unanswered.
        h.nodeSession.emitCliResponse("-75.1", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertEquals("Test Node", vm.state.value.name)
        assertFalse(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun evictedLatitudePublishesAsLongitude() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()
        assertEquals("Test Node", vm.state.value.name)

        vm.loadIdentity()
        runCurrent()
        val latCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(latCommand.endsWith("get lat"))
        val wirePrefix = latCommand.removeSuffix("get lat")
        h.nodeSession.emitCliResponse("${wirePrefix}38.5", h.session.publicKey.copyOf(6))
        runCurrent()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)

        suspend fun answerRadio() {
            val command = h.nodeSession.sendCommandCalls.last().second
            assertTrue(command.endsWith("get radio"))
            val prefix = command.removeSuffix("get radio")
            h.nodeSession.emitCliResponse("${prefix}seen", h.session.publicKey.copyOf(6))
            runCurrent()
        }

        // The prefetched name is not a CLI reply, so latitude is the oldest text.
        // Fifteen later radio replies leave 38.5 inside the 16-reply window.
        // The sixteenth pushes it out.
        val repliesThatLeaveTheLatitudeInside = 15
        val repliesThatEvictTheLatitude = 16
        repeat(repliesThatLeaveTheLatitudeInside) {
            vm.loadRadio()
            runCurrent()
            answerRadio()
        }
        h.nodeSession.emitCliResponse("38.5", h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        vm.loadRadio()
        runCurrent()
        answerRadio()
        val radioReads = h.nodeSession.sendCommandCalls.count { it.second.endsWith("get radio") }
        assertEquals(repliesThatEvictTheLatitude, radioReads)

        h.nodeSession.emitCliResponse("38.5", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("38.5", vm.state.value.longitudeText)
        assertEquals("Test Node", vm.state.value.name)
        assertFalse(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }
}

/**
 * A late behavior or identity reply fills only the query that is still open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsLateReplyTest {
    @Test
    fun timelyLongitudeDoesNotPublishAsLatitude() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadIdentity()
        runCurrent()

        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        answerCli(h.nodeSession, h.session.publicKey, "get lon", "-75.1")

        assertEquals("Test Node", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun stalePrefixedLatitudeStillFillsAfterTheWaiterDropsIt() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()

        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("get lon"))
        val wirePrefix = command.removeSuffix("get lon")
        val stalePrefix = if (wirePrefix == "00|") "FF|" else "00|"
        h.nodeSession.emitCliResponse("${stalePrefix}38.5", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Test Node", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun lateRepeatReplyPublishesWhenTheOtherBehaviorQueriesAreUnanswered() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        // Repeat, both advert intervals, and flood max each wait out.
        advanceTimeBy(CLI_TIMEOUT_STEP * 4)
        runCurrent()
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.repeaterEnabled)

        // "on" is a repeat mode and not an interval or a hop count, so it belongs to get repeat.
        h.nodeSession.emitCliResponse("on", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun ambiguousLateIntervalDoesNotPublishAnyBehaviorField() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        advanceTimeBy(CLI_TIMEOUT_STEP * 4)
        runCurrent()
        assertTrue(vm.state.value.behaviorSectionError)

        // 120 is a valid advert interval, flood interval, and hop count, so it belongs to none of them.
        h.nodeSession.emitCliResponse("120", h.session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.repeaterEnabled)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun repeatedAdvertIntervalDoesNotPublishAsFloodMax() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        runCurrent()

        answerCli(h.nodeSession, h.session.publicKey, "get repeat", "on")
        answerCli(h.nodeSession, h.session.publicKey, "get advert.interval", "120")
        answerCli(h.nodeSession, h.session.publicKey, "get flood.advert.interval", "2")
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        // 120 was the advert reply, so the same text cannot fill flood max.
        h.nodeSession.emitCliResponse("120", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun repeatedFloodMaxDoesNotPublishAsAdvertInterval() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        runCurrent()

        answerCli(h.nodeSession, h.session.publicKey, "get repeat", "on")
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        answerCli(h.nodeSession, h.session.publicKey, "get flood.advert.interval", "2")
        answerCli(h.nodeSession, h.session.publicKey, "get flood.max", "3")

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        // 3 was the flood max reply, so the same text cannot fill the advert interval.
        h.nodeSession.emitCliResponse("3", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun laterFloodMaxPublishesAfterARepeatedAdvertIntervalIsIgnored() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        runCurrent()

        answerCli(h.nodeSession, h.session.publicKey, "get repeat", "on")
        answerCli(h.nodeSession, h.session.publicKey, "get advert.interval", "120")
        answerCli(h.nodeSession, h.session.publicKey, "get flood.advert.interval", "2")
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        h.nodeSession.emitCliResponse("120", h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        // The repeated advert was ignored, so flood max is still unanswered.
        h.nodeSession.emitCliResponse("4", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("4", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun evictedAdvertIntervalPublishesAsFloodMax() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        runCurrent()

        answerCli(h.nodeSession, h.session.publicKey, "get repeat", "on")
        answerCli(h.nodeSession, h.session.publicKey, "get advert.interval", "120")
        answerCli(h.nodeSession, h.session.publicKey, "get flood.advert.interval", "2")
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertEquals("", vm.state.value.floodMaxHopsText)

        // Repeat is remembered ahead of the advert text. Fourteen later radio replies
        // evict repeat and leave 120 inside the 16-reply window. The fifteenth pushes 120 out.
        val repliesThatLeaveTheAdvertInside = 14
        val repliesThatEvictTheAdvert = 15
        repeat(repliesThatLeaveTheAdvertInside) {
            vm.loadRadio()
            runCurrent()
            answerCli(h.nodeSession, h.session.publicKey, "get radio", "seen")
        }
        h.nodeSession.emitCliResponse("120", h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        vm.loadRadio()
        runCurrent()
        answerCli(h.nodeSession, h.session.publicKey, "get radio", "seen")
        val radioReads = h.nodeSession.sendCommandCalls.count { it.second.endsWith("get radio") }
        assertEquals(repliesThatEvictTheAdvert, radioReads)

        h.nodeSession.emitCliResponse("120", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("120", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun lateOwnerInfoReplyDoesNotPublishWhenTheQueryTimedOut() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoError = IllegalStateException("offline")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()
        assertEquals("", vm.state.value.ownerInfo)

        vm.loadContactInfo()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.contactInfoSectionError)
        assertEquals("", vm.state.value.ownerInfo)

        h.nodeSession.emitCliResponse("Village hall", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.ownerInfo)
        assertTrue(vm.state.value.contactInfoSectionError)
    }

    @Test
    fun lateNameReplyDoesNotPublishWhenTheQueryTimedOut() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoError = IllegalStateException("offline")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()
        assertEquals("", vm.state.value.name)

        vm.loadIdentity()
        // Name, latitude, and longitude each wait out once the binary read has failed.
        advanceTimeBy(CLI_TIMEOUT_STEP * 3)
        runCurrent()
        assertTrue(vm.state.value.identitySectionError)
        assertEquals("", vm.state.value.name)

        h.nodeSession.emitCliResponse("Ridge Relay", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)
    }

    @Test
    fun lateNameReplyLeavesTheNameEmptyWhenTheCoordinatesAreStored() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoError = IllegalStateException("offline")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()
        assertEquals("", vm.state.value.name)

        vm.loadIdentity()
        // get name times out. Latitude is the query still in flight.
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        h.nodeSession.emitCliResponse("38.5", h.session.publicKey.copyOf(6))
        runCurrent()
        h.nodeSession.emitCliResponse("-75.1", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityLoaded)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)

        // get name is not machine-checked, so a later reply cannot fill the name.
        h.nodeSession.emitCliResponse("Ridge Relay", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityLoaded)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }
}

/**
 * Region list and default-scope replies publish after the read is in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsRegionLoadTest {
    @Test
    fun lateRegionListPublishesAfterTheReadTimesOut() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.regionsSectionError)
        assertTrue(vm.state.value.regions.isEmpty())

        // A region dump is not a behavior reply. After the read times out it belongs to that list.
        h.nodeSession.emitCliResponse("* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.regionsSectionError)
        assertTrue(vm.state.value.regionsLoaded)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
    }

    @Test
    fun stalePrefixedRegionListStillFillsWhileRadioIsInFlight() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.regionsSectionError)
        assertTrue(vm.state.value.regions.isEmpty())

        vm.loadRadio()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("get radio"))
        val wirePrefix = command.removeSuffix("get radio")
        val stalePrefix = if (wirePrefix == "00|") "FF|" else "00|"
        h.nodeSession.emitCliResponse("$stalePrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.regionsLoaded)
        assertFalse(vm.state.value.regionsSectionError)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
    }

    @Test
    fun defaultScopePublishesAfterTheRegionListLoads() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        assertEquals("v1.15.0", vm.state.value.firmwareVersion)
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(regionCommand.endsWith("region"))
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val scopeCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(scopeCommand.endsWith("region default"))
        val scopePrefix = scopeCommand.removeSuffix("region default")
        h.nodeSession.emitCliResponse("${scopePrefix}default scope is duckburg", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("duckburg", vm.state.value.defaultScopeName)
        assertTrue(vm.state.value.defaultScopeLoaded)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertFalse(vm.state.value.regionsSectionError)
    }

    @Test
    fun chosenDefaultScopeSendsRegionDefault() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n pond\n", h.session.publicKey.copyOf(6))
        runCurrent()
        val scopeCommand = h.nodeSession.sendCommandCalls.last().second
        val scopePrefix = scopeCommand.removeSuffix("region default")
        h.nodeSession.emitCliResponse("${scopePrefix}default scope is duckburg", h.session.publicKey.copyOf(6))
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setDefaultScope("pond")
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region default pond"))
        val prefix = command.removeSuffix("region default pond")
        h.nodeSession.emitCliResponse("${prefix}default scope is now pond", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("pond", vm.state.value.defaultScopeName)
        assertTrue(vm.state.value.defaultScopeLoaded)
        assertTrue(vm.state.value.regions.first { it.name == "pond" }.floodAllowed)
        assertTrue(vm.state.value.regions.first { it.name == "duckburg" }.floodAllowed)
        assertEquals(ApplyBanner.Success, vm.state.value.applyBanner)
    }

    @Test
    fun olderFirmwareLoadsRegionsWithoutAskingForDefaultScope() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.14.1")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        assertEquals("v1.14.1", vm.state.value.firmwareVersion)
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(regionCommand.endsWith("region"))
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.endsWith("region default") })
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertFalse(vm.state.value.defaultScopeLoaded)
        assertEquals(null, vm.state.value.defaultScopeName)
        assertFalse(vm.state.value.regionsSectionError)
    }

    @Test
    fun clearedDefaultScopePublishesLoadedWithNoName() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(regionCommand.endsWith("region"))
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val scopeCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(scopeCommand.endsWith("region default"))
        val scopePrefix = scopeCommand.removeSuffix("region default")
        h.nodeSession.emitCliResponse("${scopePrefix}default scope is <null>", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.defaultScopeLoaded)
        assertEquals(null, vm.state.value.defaultScopeName)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertFalse(vm.state.value.regionsSectionError)
    }

    @Test
    fun defaultScopeTimeoutStillPublishesTheRegionList() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoResponse = testOwnerInfoResponse(firmwareVersion = "v1.15.0")
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(regionCommand.endsWith("region"))
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(h.nodeSession.sendCommandCalls.last().second.endsWith("region default"))
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()

        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertFalse(vm.state.value.defaultScopeLoaded)
        assertEquals(null, vm.state.value.defaultScopeName)
        assertFalse(vm.state.value.regionsSectionError)
    }

    @Test
    fun typingANewRegionNameDoesNotRadio() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
        )
        runCurrent()
        val traffic = h.nodeSession.radioTraffic()

        vm.setNewRegionName("pond")
        runCurrent()

        assertEquals(traffic, h.nodeSession.radioTraffic())
        assertEquals("pond", vm.state.value.newRegionName)
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(null, vm.state.value.applyBanner)
        assertTrue(vm.state.value.regions.isEmpty())
    }

    @Test
    fun addedRegionPublishesFloodAllowed() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(regionCommand.endsWith("region"))
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()
        assertTrue(vm.state.value.regionsLoaded)

        vm.setNewRegionName("pond")
        vm.addRegion()
        runCurrent()
        val putCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(putCommand.endsWith("region put pond"))
        val putPrefix = putCommand.removeSuffix("region put pond")
        h.nodeSession.emitCliResponse("${putPrefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        val added = vm.state.value.regions.first { it.name == "pond" }
        assertTrue(added.floodAllowed)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertTrue(vm.state.value.regionsLoaded)
        assertTrue(vm.state.value.hasUnsavedRegionChanges)
        assertEquals("", vm.state.value.newRegionName)
    }

    @Test
    fun rejectedRegionAddKeepsTheTypedName() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(regionCommand.endsWith("region"))
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()
        assertTrue(vm.state.value.regionsLoaded)

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setNewRegionName("pond")
        vm.addRegion()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val putCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(putCommand.endsWith("region put pond"))
        val putPrefix = putCommand.removeSuffix("region put pond")
        h.nodeSession.emitCliResponse("${putPrefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.regions.none { it.name == "pond" })
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertEquals("pond", vm.state.value.newRegionName)
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(ApplyBanner.RegionAddFailed, vm.state.value.applyBanner)
        assertTrue(vm.state.value.regionsLoaded)
    }

    @Test
    fun nonRegionReplyLeavesTheRegionSectionInError() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.regionsSectionError)

        h.nodeSession.emitCliResponse("not a region", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.regionsSectionError)
        assertFalse(vm.state.value.regionsLoaded)
        assertTrue(vm.state.value.regions.isEmpty())
    }

    @Test
    fun cleanupDropsTheHandlerSoALaterReplyDoesNotFillTheScreen() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRadio()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        assertTrue(vm.state.value.radioSectionError)

        vm.cleanup()
        h.nodeSession.emitCliResponse("915.0,250.0,10,5", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
        assertTrue(vm.state.value.radioSectionError)
    }
}

/**
 * Password, radio, and reboot commands send only after the local edit is valid.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsApplyCommandsTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun editingFieldsWhileDisconnectedLeavesLocalStateConsistent() = runBlocking {
        // The sheet outlives a radio drop: a keystroke afterwards must not raise.
        val vm = NodeSettingsViewModel(
            scope = scope,
            sessionId = sessionId,
            dependencies = NodeSettingsViewModel.Dependencies(repeaterAdminService = { null }),
        )

        vm.setName("relay")
        vm.setLatitudeText("12.5")
        vm.setOwnerInfo("someone")
        vm.setNewPassword("pw")
        vm.setAdvertIntervalText("120")

        val state = vm.state.value
        assertEquals("relay", state.name)
        assertEquals("12.5", state.latitudeText)
        assertEquals("someone", state.ownerInfo)
        assertEquals("pw", state.newPassword)
        assertEquals("120", state.advertIntervalText)
    }

    @Test
    fun ownerInfoBeyondTheFirmwareCapIsFlagged() = runBlocking {
        val vm = NodeSettingsViewModel(
            scope = scope,
            sessionId = sessionId,
            dependencies = NodeSettingsViewModel.Dependencies(repeaterAdminService = { null }),
        )

        vm.setOwnerInfo("x".repeat(NodeSettingsValidation.OWNER_INFO_MAX_LENGTH))
        assertFalse(vm.state.value.ownerInfoTooLong)
        assertEquals(NodeSettingsValidation.OWNER_INFO_MAX_LENGTH, vm.state.value.ownerInfo.length)

        vm.setOwnerInfo("x".repeat(NodeSettingsValidation.OWNER_INFO_MAX_LENGTH + 1))
        assertTrue(vm.state.value.ownerInfoTooLong)
        assertEquals(NodeSettingsValidation.OWNER_INFO_MAX_LENGTH + 1, vm.state.value.ownerInfo.length)
    }

    @Test
    fun changedPasswordClearsBothFields() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setNewPassword("harbor9")
        vm.setConfirmPassword("harbor9")
        vm.changePassword()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("password harbor9"))
        val prefix = command.removeSuffix("password harbor9")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)
        assertTrue(vm.state.value.changePasswordSuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedPasswordKeepsBothFields() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setNewPassword("harbor9")
        vm.setConfirmPassword("harbor9")
        vm.changePassword()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("password harbor9"))
        val prefix = command.removeSuffix("password harbor9")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("harbor9", vm.state.value.newPassword)
        assertEquals("harbor9", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.changePasswordSuccess)
        assertEquals(ApplyBanner.PasswordFailed, vm.state.value.applyBanner)
    }

    @Test
    fun appliedAdvertIntervalSendsTheSetCommand() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setAdvertIntervalText("120")
        vm.applyBehavior()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set advert.interval 120"))
        val prefix = command.removeSuffix("set advert.interval 120")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("120", vm.state.value.advertIntervalText)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun appliedFloodAdvertIntervalSendsTheSetCommand() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setFloodAdvertIntervalText("6")
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set flood.advert.interval 6"))
        val prefix = command.removeSuffix("set flood.advert.interval 6")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("6", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun appliedFloodMaxSendsTheSetCommand() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setFloodMaxHopsText("8")
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set flood.max 8"))
        val prefix = command.removeSuffix("set flood.max 8")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("8", vm.state.value.floodMaxHopsText)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun mismatchedPasswordDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setNewPassword("harbor9")
        vm.setConfirmPassword("otherkey")
        vm.changePassword()
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertEquals("harbor9", vm.state.value.newPassword)
        assertEquals("otherkey", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.changePasswordSuccess)
        assertEquals(ApplyBanner.PasswordMismatch, vm.state.value.applyBanner)
    }

    @Test
    fun emptyPasswordDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.changePassword()
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.changePasswordSuccess)
        assertEquals(ApplyBanner.PasswordEmpty, vm.state.value.applyBanner)
    }

    @Test
    fun applyRadioPublishesTheChosenParameters() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setFrequencyText("869.5")
        vm.setBandwidth(125.0)
        vm.setSpreadingFactor(9)
        vm.setCodingRate(5)
        vm.applyRadio()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set radio 869.5,125.0,9,5"))
        val prefix = command.removeSuffix("set radio 869.5,125.0,9,5")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("869.5", vm.state.value.frequencyText)
        assertEquals("125", vm.state.value.bandwidthText)
        assertEquals("9", vm.state.value.spreadingFactorText)
        assertEquals("5", vm.state.value.codingRateText)
        assertFalse(vm.state.value.radioModified)
        assertEquals(ApplyBanner.RadioApplied, vm.state.value.applyBanner)
    }

    @Test
    fun setLatitudePublishesTheDegreeValue() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setLatitudeText("40.7")
        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set lat 40.7"))
        val prefix = command.removeSuffix("set lat 40.7")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("40.7", vm.state.value.latitudeText)
        assertEquals("Test Node", vm.state.value.name)
        assertEquals("", vm.state.value.longitudeText)
        assertFalse(vm.state.value.identityModified)
        assertTrue(vm.state.value.identityApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun setLongitudePublishesTheDegreeValue() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setLongitudeText("-75.1")
        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set lon -75.1"))
        val prefix = command.removeSuffix("set lon -75.1")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("-75.1", vm.state.value.longitudeText)
        assertEquals("Test Node", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertFalse(vm.state.value.identityModified)
        assertTrue(vm.state.value.identityApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun dismissedRebootConfirmDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setShowRebootConfirm(true)
        assertTrue(vm.state.value.showRebootConfirm)

        vm.setShowRebootConfirm(false)
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertFalse(vm.state.value.showRebootConfirm)
        assertFalse(vm.state.value.isRebooting)
        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.endsWith("reboot") })
    }

    @Test
    fun silentRebootStillShowsSent() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setShowRebootConfirm(true)
        vm.confirmReboot()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("reboot"))
        assertFalse(vm.state.value.showRebootConfirm)
        assertTrue(vm.state.value.isRebooting)

        advanceTimeBy(3.seconds)
        runCurrent()

        assertEquals(ApplyBanner.RebootSent, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isRebooting)
    }
}

/**
 * Name, owner, repeat, and flood edits send the matching repeater command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsIdentityBehaviorTest {
    @Test
    fun savedRegionsClearsTheUnsavedFlag() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.setNewRegionName("pond")
        vm.addRegion()
        runCurrent()
        val putCommand = h.nodeSession.sendCommandCalls.last().second
        val putPrefix = putCommand.removeSuffix("region put pond")
        h.nodeSession.emitCliResponse("${putPrefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()
        assertTrue(vm.state.value.hasUnsavedRegionChanges)

        vm.saveRegions()
        runCurrent()
        val saveCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(saveCommand.endsWith("region save"))
        val savePrefix = saveCommand.removeSuffix("region save")
        h.nodeSession.emitCliResponse("${savePrefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertTrue(vm.state.value.regionsSaveSuccess)
        assertEquals(null, vm.state.value.applyBanner)
        assertTrue(vm.state.value.regions.any { it.name == "pond" })
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
    }

    @Test
    fun rejectedRegionSaveKeepsTheUnsavedFlag() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.setNewRegionName("pond")
        vm.addRegion()
        runCurrent()
        val putCommand = h.nodeSession.sendCommandCalls.last().second
        val putPrefix = putCommand.removeSuffix("region put pond")
        h.nodeSession.emitCliResponse("${putPrefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()
        assertTrue(vm.state.value.hasUnsavedRegionChanges)
        assertEquals("", vm.state.value.newRegionName)

        vm.saveRegions()
        runCurrent()
        val saveCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(saveCommand.endsWith("region save"))
        val savePrefix = saveCommand.removeSuffix("region save")
        h.nodeSession.emitCliResponse("${savePrefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.hasUnsavedRegionChanges)
        assertFalse(vm.state.value.regionsSaveSuccess)
        assertFalse(vm.state.value.isSavingRegions)
        assertEquals(ApplyBanner.RegionsSaveFailed, vm.state.value.applyBanner)
        assertTrue(vm.state.value.regions.any { it.name == "pond" })
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
    }

    @Test
    fun enabledRepeaterSendsSetRepeatOn() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setRepeaterEnabled(true)
        vm.applyBehavior()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set repeat on"))
        val prefix = command.removeSuffix("set repeat on")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun disabledRepeaterSendsSetRepeatOff() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        h.nodeSession.emitCliResponse("on", h.session.publicKey.copyOf(6))
        runCurrent()
        h.nodeSession.emitCliResponse("90", h.session.publicKey.copyOf(6))
        runCurrent()
        h.nodeSession.emitCliResponse("12", h.session.publicKey.copyOf(6))
        runCurrent()
        h.nodeSession.emitCliResponse("8", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        assertEquals("90", vm.state.value.advertIntervalText)
        assertEquals("12", vm.state.value.floodAdvertIntervalText)
        assertEquals("8", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setRepeaterEnabled(false)
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set repeat off"))
        val prefix = command.removeSuffix("set repeat off")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.repeaterEnabled)
        assertEquals("90", vm.state.value.advertIntervalText)
        assertEquals("12", vm.state.value.floodAdvertIntervalText)
        assertEquals("8", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedRepeaterOffKeepsTheSwitchOff() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        h.nodeSession.emitCliResponse("on", h.session.publicKey.copyOf(6))
        runCurrent()
        h.nodeSession.emitCliResponse("90", h.session.publicKey.copyOf(6))
        runCurrent()
        h.nodeSession.emitCliResponse("12", h.session.publicKey.copyOf(6))
        runCurrent()
        h.nodeSession.emitCliResponse("8", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.repeaterEnabled)
        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setRepeaterEnabled(false)
        assertTrue(vm.state.value.behaviorModified)
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set repeat off"))
        val prefix = command.removeSuffix("set repeat off")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.repeaterEnabled)
        assertEquals("90", vm.state.value.advertIntervalText)
        assertEquals("12", vm.state.value.floodAdvertIntervalText)
        assertEquals("8", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorApplySuccess)
        assertFalse(vm.state.value.isApplyingBehavior)
        assertEquals(ApplyBanner.PartialFailure, vm.state.value.applyBanner)
    }

    @Test
    fun renamedRepeaterSendsSetName() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setName("Ridge")
        vm.applyIdentity()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set name Ridge"))
        val prefix = command.removeSuffix("set name Ridge")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Ridge", vm.state.value.name)
        assertFalse(vm.state.value.identityModified)
        assertTrue(vm.state.value.identityApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedRenameKeepsTheTypedName() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setName("Ridge")
        assertTrue(vm.state.value.identityModified)
        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set name Ridge"))
        val prefix = command.removeSuffix("set name Ridge")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Ridge", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityApplySuccess)
        assertFalse(vm.state.value.isApplyingIdentity)
        assertEquals(ApplyBanner.PartialFailure, vm.state.value.applyBanner)
    }

    @Test
    fun overlongNameDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val tooLong = "x".repeat(MAX_USABLE_NAME_BYTES + 1)
        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setName(tooLong)
        assertTrue(vm.state.value.identityModified)

        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.contains("set name") })
        assertTrue(vm.state.value.identityValidation?.nameTooLong == true)
        assertEquals(tooLong, vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityApplySuccess)
        assertFalse(vm.state.value.isApplyingIdentity)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun dismissedFailureNoticeDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setName("Ridge")
        vm.applyIdentity()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set name Ridge"))
        val prefix = command.removeSuffix("set name Ridge")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals(ApplyBanner.PartialFailure, vm.state.value.applyBanner)

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.clearApplyBanner()
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertEquals(null, vm.state.value.applyBanner)
        assertEquals("Ridge", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityApplySuccess)
        assertFalse(vm.state.value.isApplyingIdentity)
    }

    @Test
    fun changedOwnerInfoSendsSetOwnerInfo() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setOwnerInfo("shed")
        vm.applyContactInfo()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set owner.info shed"))
        val prefix = command.removeSuffix("set owner.info shed")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("shed", vm.state.value.ownerInfo)
        assertFalse(vm.state.value.contactInfoModified)
        assertTrue(vm.state.value.contactInfoApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedOwnerInfoKeepsTheTypedText() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setOwnerInfo("shed")
        vm.applyContactInfo()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set owner.info shed"))
        val prefix = command.removeSuffix("set owner.info shed")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("shed", vm.state.value.ownerInfo)
        assertTrue(vm.state.value.contactInfoModified)
        assertFalse(vm.state.value.contactInfoApplySuccess)
        assertFalse(vm.state.value.isApplyingContactInfo)
        assertEquals(ApplyBanner.PartialFailure, vm.state.value.applyBanner)
    }

    @Test
    fun deniedFloodMarksUnsavedChanges() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.toggleRegionFlood("duckburg")
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region denyf duckburg"))
        val prefix = command.removeSuffix("region denyf duckburg")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        val duckburg = vm.state.value.regions.first { it.name == "duckburg" }
        assertFalse(duckburg.floodAllowed)
        assertTrue(vm.state.value.hasUnsavedRegionChanges)
        assertTrue(vm.state.value.regionsLoaded)
        assertEquals(ApplyBanner.Success, vm.state.value.applyBanner)
    }

    @Test
    fun allowedFloodMarksUnsavedChanges() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val before = vm.state.value.regions.first { it.name == "duckburg" }
        assertFalse(before.floodAllowed)

        vm.toggleRegionFlood("duckburg")
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region allowf duckburg"))
        val prefix = command.removeSuffix("region allowf duckburg")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        val duckburg = vm.state.value.regions.first { it.name == "duckburg" }
        assertTrue(duckburg.floodAllowed)
        assertTrue(vm.state.value.regions.first { it.name == "*" }.floodAllowed)
        assertTrue(vm.state.value.hasUnsavedRegionChanges)
        assertTrue(vm.state.value.regionsLoaded)
        assertEquals(ApplyBanner.Success, vm.state.value.applyBanner)
    }
}

/**
 * Region add, remove, and child-parent edits send only the intended command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsRegionEditTest {
    @Test
    fun rejectedFloodAllowKeepsFloodOff() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val before = vm.state.value.regions.first { it.name == "duckburg" }
        assertFalse(before.floodAllowed)

        vm.toggleRegionFlood("duckburg")
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region allowf duckburg"))
        val prefix = command.removeSuffix("region allowf duckburg")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        val duckburg = vm.state.value.regions.first { it.name == "duckburg" }
        assertFalse(duckburg.floodAllowed)
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertTrue(vm.state.value.regionsLoaded)
        assertEquals(ApplyBanner.RegionUnknown, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedFloodDenyKeepsFloodOn() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val before = vm.state.value.regions.first { it.name == "duckburg" }
        assertTrue(before.floodAllowed)

        vm.toggleRegionFlood("duckburg")
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region denyf duckburg"))
        val prefix = command.removeSuffix("region denyf duckburg")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        val duckburg = vm.state.value.regions.first { it.name == "duckburg" }
        assertTrue(duckburg.floodAllowed)
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertTrue(vm.state.value.regionsLoaded)
        assertEquals(ApplyBanner.RegionUnknown, vm.state.value.applyBanner)
    }

    @Test
    fun removedRegionDropsThatRow() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.removeRegion("duckburg")
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region remove duckburg"))
        val prefix = command.removeSuffix("region remove duckburg")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertFalse(vm.state.value.regions.any { it.name == "duckburg" })
        assertTrue(vm.state.value.regions.any { it.name == "*" })
        assertTrue(vm.state.value.hasUnsavedRegionChanges)
        assertTrue(vm.state.value.regionsLoaded)
        assertEquals(ApplyBanner.Success, vm.state.value.applyBanner)
    }

    @Test
    fun firmwareNotEmptyKeepsTheRegion() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.removeRegion("duckburg")
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region remove duckburg"))
        val prefix = command.removeSuffix("region remove duckburg")
        h.nodeSession.emitCliResponse("${prefix}region not empty", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertTrue(vm.state.value.regions.any { it.name == "*" })
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(null, vm.state.value.blockedDeleteRegionName)
        assertTrue(vm.state.value.regionsLoaded)
        assertEquals(ApplyBanner.RegionNotEmpty, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedRegionRemoveKeepsTheRegion() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.removeRegion("duckburg")
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region remove duckburg"))
        val prefix = command.removeSuffix("region remove duckburg")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertTrue(vm.state.value.regions.any { it.name == "*" })
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(null, vm.state.value.blockedDeleteRegionName)
        assertTrue(vm.state.value.regionsLoaded)
        assertEquals(ApplyBanner.RegionRemoveFailed, vm.state.value.applyBanner)
    }

    @Test
    fun parentRegionDeleteStaysBlocked() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n  pond F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.removeRegion("duckburg")
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertEquals("duckburg", vm.state.value.blockedDeleteRegionName)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertTrue(vm.state.value.regions.any { it.name == "pond" })
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
    }

    @Test
    fun dismissedBlockedDeleteClearsTheName() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n  pond F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.removeRegion("duckburg")
        runCurrent()
        assertEquals("duckburg", vm.state.value.blockedDeleteRegionName)

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.dismissBlockedDelete()
        runCurrent()

        assertEquals(null, vm.state.value.blockedDeleteRegionName)
        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertTrue(vm.state.value.regions.any { it.name == "pond" })
    }

    @Test
    fun startedChildRegionDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n  pond F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val duckburg = vm.state.value.regions.first { it.name == "duckburg" }
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.beginAddChild(duckburg)
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.contains("region put") })
        assertEquals(RepeaterRegionEntry.Parent.Named("duckburg"), vm.state.value.newRegionParent)
        assertEquals("", vm.state.value.newRegionName)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertTrue(vm.state.value.regions.any { it.name == "pond" })
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(null, vm.state.value.applyBanner)
        assertEquals(null, vm.state.value.blockedDeleteRegionName)
    }

    @Test
    fun chosenRegionParentDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setNewRegionParent(RepeaterRegionEntry.Parent.Named("duckburg"))
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.contains("region put") })
        assertEquals(RepeaterRegionEntry.Parent.Named("duckburg"), vm.state.value.newRegionParent)
        assertEquals("", vm.state.value.newRegionName)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun allTrafficChildClearsTheChosenParent() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.setNewRegionParent(RepeaterRegionEntry.Parent.Named("duckburg"))
        assertEquals(RepeaterRegionEntry.Parent.Named("duckburg"), vm.state.value.newRegionParent)
        val wildcard = vm.state.value.regions.first { it.name == "*" }

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.beginAddChild(wildcard)
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.contains("region put") })
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)
        assertEquals("", vm.state.value.newRegionName)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertFalse(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun addedChildRegionSendsPutWithThatParent() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRegions()
        runCurrent()
        val regionCommand = h.nodeSession.sendCommandCalls.last().second
        val regionPrefix = regionCommand.removeSuffix("region")
        h.nodeSession.emitCliResponse("$regionPrefix* F\n duckburg F\n", h.session.publicKey.copyOf(6))
        runCurrent()

        val duckburg = vm.state.value.regions.first { it.name == "duckburg" }
        vm.beginAddChild(duckburg)
        vm.setNewRegionName("pond")
        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.addRegion()
        runCurrent()

        assertEquals(sentBefore + 1, h.nodeSession.sendCommandCalls.size)
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("region put pond duckburg"))
        val prefix = command.removeSuffix("region put pond duckburg")
        h.nodeSession.emitCliResponse("${prefix}OK", h.session.publicKey.copyOf(6))
        runCurrent()

        val added = vm.state.value.regions.first { it.name == "pond" }
        assertEquals("duckburg", added.parentName)
        assertEquals(2, added.depth)
        assertTrue(added.floodAllowed)
        assertTrue(vm.state.value.regions.any { it.name == "duckburg" })
        assertEquals("", vm.state.value.newRegionName)
        assertEquals(RepeaterRegionEntry.Parent.Unscoped, vm.state.value.newRegionParent)
        assertTrue(vm.state.value.hasUnsavedRegionChanges)
        assertEquals(ApplyBanner.Success, vm.state.value.applyBanner)
    }
}

/**
 * Out-of-range behavior and radio apply surface the banner without a silent write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeSettingsRangeBannerTest {
    @Test
    fun applyBehaviorSurfacesTheOutOfRangeField() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.setAdvertIntervalText("5")
        vm.applyBehavior()
        runCurrent()

        val validation = vm.state.value.behaviorValidation
        assertTrue(validation?.advertIntervalInvalid == true)
        assertEquals(null, vm.state.value.applyBanner)

        vm.setAdvertIntervalText("120")
        assertEquals(null, vm.state.value.behaviorValidation)
    }

    @Test
    fun outOfRangeFloodMaxDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setFloodMaxHopsText("65")
        assertTrue(vm.state.value.behaviorModified)
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.contains("set flood.max") })
        assertEquals("65", vm.state.value.floodMaxHopsText)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertTrue(vm.state.value.behaviorValidation?.floodMaxHopsInvalid == true)
        assertFalse(vm.state.value.behaviorApplySuccess)
        assertFalse(vm.state.value.isApplyingBehavior)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun outOfRangeFloodAdvertIntervalDoesNotSend() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        val sentBefore = h.nodeSession.sendCommandCalls.size
        vm.setFloodAdvertIntervalText("1")
        assertTrue(vm.state.value.behaviorModified)
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore, h.nodeSession.sendCommandCalls.size)
        assertTrue(h.nodeSession.sendCommandCalls.none { it.second.contains("set flood.advert.interval") })
        assertEquals("1", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorValidation?.floodIntervalInvalid == true)
        assertFalse(vm.state.value.behaviorApplySuccess)
        assertFalse(vm.state.value.isApplyingBehavior)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun applyRadioBannersTheRestartRequirement() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRadio()
        runCurrent()
        h.nodeSession.emitCliResponse("915.0,250.0,10,5", h.session.publicKey.copyOf(6))
        runCurrent()
        vm.setSpreadingFactor(11)
        vm.applyRadio()
        runCurrent()
        h.nodeSession.emitCliResponse("OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.RadioApplied, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedRadioApplyDoesNotShowTheRestartNotice() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadRadio()
        runCurrent()
        val getCommand = h.nodeSession.sendCommandCalls.last().second
        assertTrue(getCommand.endsWith("get radio"))
        val getPrefix = getCommand.removeSuffix("get radio")
        h.nodeSession.emitCliResponse("${getPrefix}915.0,250.0,10,5", h.session.publicKey.copyOf(6))
        runCurrent()

        vm.setSpreadingFactor(11)
        vm.applyRadio()
        runCurrent()
        val command = h.nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set radio 915.0,250.0,11,5"))
        val prefix = command.removeSuffix("set radio 915.0,250.0,11,5")
        h.nodeSession.emitCliResponse("${prefix}ERR: denied", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("915.0", vm.state.value.frequencyText)
        assertEquals("11", vm.state.value.spreadingFactorText)
        assertEquals("5", vm.state.value.codingRateText)
        assertTrue(vm.state.value.radioModified)
        assertFalse(vm.state.value.isApplyingRadio)
        assertEquals(ApplyBanner.RadioApplyFailed, vm.state.value.applyBanner)
    }

    @Test
    fun deviceInfoPublishesTheClockAsEpochSeconds() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        vm.loadDeviceInfo()
        runCurrent()
        h.nodeSession.emitCliResponse(DEVICE_CLOCK_REPLY, h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(DEVICE_CLOCK_EPOCH_SECONDS, vm.state.value.deviceTimeEpochSeconds)
    }

    @Test
    fun deviceInfoPublishesClockDriftFromTheNodeClock() = runTest {
        val h = connectedHarness(backgroundScope)
        val now = DEVICE_CLOCK_EPOCH_SECONDS - 400
        val vm = NodeSettingsViewModel(
            backgroundScope,
            h.session.id,
            NodeSettingsViewModel.Dependencies { h.admin },
            nowEpochSeconds = { now },
        )
        runCurrent()
        vm.loadDeviceInfo()
        runCurrent()
        h.nodeSession.emitCliResponse(DEVICE_CLOCK_REPLY, h.session.publicKey.copyOf(6))
        runCurrent()
        assertEquals(400.0, vm.state.value.clockDriftSeconds)
    }

    @Test
    fun binaryOwnerInfoPreFetchFillsNameAndContactInfo() = runTest {
        val h = connectedHarness(backgroundScope)
        h.nodeSession.ownerInfoResponse = testOwnerInfoResponse(
            firmwareVersion = "v1.12.0",
            nodeName = "Ridge Relay",
            ownerInfo = "ops@example",
        )
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        vm.setContactInfoExpanded(true)
        runCurrent()

        assertEquals(1, h.nodeSession.requestOwnerInfoCalls.size)
        assertEquals("v1.12.0", vm.state.value.firmwareVersion)
        assertEquals("Ridge Relay", vm.state.value.name)
        assertEquals("ops@example", vm.state.value.ownerInfo)
    }

    @Test
    fun cleanupReleasesTheSharedCliHandler() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()

        var later = 0
        vm.cleanup()
        h.admin.setCLIHandler { _, _ -> later++ }
        h.nodeSession.emitCliResponse("OK", h.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(1, later)
    }

    @Test
    fun cleanupClearsTheServiceWhereTheHandlerWasRegistered() = runTest {
        val original = connectedHarness(backgroundScope)
        val replacement = connectedHarness(backgroundScope)
        var current = original.admin
        val vm = NodeSettingsViewModel(
            backgroundScope,
            original.session.id,
            NodeSettingsViewModel.Dependencies { current },
        )
        runCurrent()

        var replacementCalls = 0
        replacement.admin.setCLIHandler { _, _ -> replacementCalls += 1 }
        current = replacement.admin

        vm.cleanup()
        replacement.nodeSession.emitCliResponse("replacement", replacement.session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(1, replacementCalls)
    }

    @Test
    fun cleanupRejectsLaterDeviceActions() = runTest {
        val h = connectedHarness(backgroundScope)
        val vm = NodeSettingsViewModel(backgroundScope, h.session.id, NodeSettingsViewModel.Dependencies { h.admin })
        runCurrent()
        val trafficBeforeCleanup = h.nodeSession.radioTraffic()

        vm.cleanup()
        vm.sendAdvert()
        vm.syncTime()
        vm.confirmReboot()
        runCurrent()

        assertEquals(trafficBeforeCleanup, h.nodeSession.radioTraffic())
    }
}

private val sessionId = Uuid.normalized("C0000000-0000-0000-0000-000000000001")

private val CLI_TIMEOUT_STEP = 11.seconds

// The firmware clock reply and its UTC epoch, so the row can render in the reader's own zone.
private const val DEVICE_CLOCK_REPLY = "12:00 - 1/1/2026 UTC"
private const val DEVICE_CLOCK_EPOCH_SECONDS = 1_767_268_800L

/** Command and read counts, so a settings action can prove it did not radio. */
private fun FakeNodeSession.radioTraffic(): String = listOf(
    "commands" to sendCommandCalls.size,
    "owner" to requestOwnerInfoCalls.size,
    "logins" to sendLoginCalls.size,
    "status" to requestStatusCalls.size,
    "telemetry" to requestTelemetryCalls.size,
    "neighbours" to requestNeighboursCalls.size,
    "messages" to getMessageCalls.size,
).joinToString(" ") { (name, count) -> "$name=$count" }

/** Repeater settings with no admin service. Opening the sheet may read that service; a later edit must not. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.quietRepeaterSettings(): QuietRepeaterSettings {
    var adminReads = 0
    val vm = NodeSettingsViewModel(
        backgroundScope,
        sessionId,
        NodeSettingsViewModel.Dependencies {
            adminReads += 1
            null
        },
    )
    runCurrent()
    val adminAfterInit = adminReads
    assertTrue(adminAfterInit > 0)
    return QuietRepeaterSettings(vm, adminAfterInit) { adminReads }
}

private class QuietRepeaterSettings(
    val vm: NodeSettingsViewModel,
    private val adminAfterInit: Int,
    private val adminReads: () -> Int,
) {
    fun assertStillQuiet() {
        assertEquals(adminAfterInit, adminReads())
    }
}

private fun disconnectedViewModel(scope: CoroutineScope) = NodeSettingsViewModel(
    scope = scope,
    sessionId = sessionId,
    dependencies = NodeSettingsViewModel.Dependencies(repeaterAdminService = { null }),
)

/** A connected admin session over fakes: what an open settings sheet sees. */
private suspend fun connectedHarness(serviceScope: CoroutineScope): Harness {
    val nodeSession = FakeNodeSession()
    val store = InMemoryRemoteNodeStore()
    val passwords = InMemoryNodePasswordStore()
    val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
    store.saveSession(session)
    store.setContact(session.radioID, testContact(publicKey = session.publicKey))
    store.setContactRouting(session.radioID, session.publicKey, isFloodRouted = true)
    val admin = RepeaterAdminService(
        RemoteNodeService(nodeSession, store, passwords, serviceScope),
        NodeCliCoordinator(nodeSession, store, passwords, serviceScope),
        nodeSession,
        store,
        passwords,
    )
    return Harness(nodeSession, admin, session)
}

private class Harness(
    val nodeSession: FakeNodeSession,
    val admin: RepeaterAdminService,
    val session: RemoteNodeSessionDto,
)

private class NodeSettingsProbeError : Error("probe")
