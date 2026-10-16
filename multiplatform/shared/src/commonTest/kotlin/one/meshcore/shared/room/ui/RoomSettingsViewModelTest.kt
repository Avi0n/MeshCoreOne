package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.MAX_USABLE_NAME_BYTES
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.NodeSettingsResponseParser
import one.meshcore.shared.remotenode.NodeSettingsValidation
import one.meshcore.shared.remotenode.RemoteCLICommandRewriter
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.ScriptedCliSender
import one.meshcore.shared.remotenode.answerCli
import one.meshcore.shared.remotenode.sendCLICommand
import one.meshcore.shared.remotenode.sendRawCLICommand
import one.meshcore.shared.remotenode.testContact
import one.meshcore.shared.remotenode.ui.APPLY_SUCCESS_FLASH_MS
import one.meshcore.shared.remotenode.ui.ApplyBanner
import one.meshcore.shared.room.FakeRoomAdminSession
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomAdminSession
import one.meshcore.shared.room.RoomSettingsController
import one.meshcore.shared.room.roomContact
import one.meshcore.shared.room.roomSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Collapsed room sections and field typing must not radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsViewModelTest {
    @Test
    fun keepingTheRoomRebootConfirmHiddenDoesNotRadio() = runTest {
        var serviceReads = 0
        val cli = scriptedSender(emptyMap())
        val vm = RoomSettingsViewModel(
            backgroundScope,
            controller(cli),
            roomAdminService = {
                serviceReads += 1
                null
            },
        )
        runCurrent()
        assertEquals(1, serviceReads)
        assertFalse(vm.state.value.showRebootConfirm)
        assertFalse(vm.state.value.deviceActionsExpanded)
        assertFalse(vm.state.value.isRebooting)
        assertFalse(vm.state.value.isSyncingTime)
        assertNull(vm.state.value.applyBanner)

        vm.setShowRebootConfirm(false)
        runCurrent()

        assertEquals(1, serviceReads)
        assertFalse(vm.state.value.showRebootConfirm)
        assertFalse(vm.state.value.deviceActionsExpanded)
        assertFalse(vm.state.value.isRebooting)
        assertFalse(vm.state.value.isSyncingTime)
        assertNull(vm.state.value.applyBanner)
    }

    @Test
    fun keepingRoomAccessCollapsedDoesNotRadio() = runTest {
        var serviceReads = 0
        val cli = scriptedSender(emptyMap())
        val vm = RoomSettingsViewModel(
            backgroundScope,
            controller(cli),
            roomAdminService = {
                serviceReads += 1
                null
            },
        )
        runCurrent()
        assertEquals(1, serviceReads)
        assertFalse(vm.state.value.roomAccessExpanded)
        assertFalse(vm.state.value.roomAccessLoaded)
        assertFalse(vm.state.value.isLoadingRoomAccess)
        assertFalse(vm.state.value.roomAccessModified)
        assertFalse(vm.state.value.roomAccessSectionError)
        assertNull(vm.state.value.applyBanner)

        vm.setRoomAccessExpanded(false)
        runCurrent()

        assertEquals(1, serviceReads)
        assertFalse(vm.state.value.roomAccessExpanded)
        assertFalse(vm.state.value.roomAccessLoaded)
        assertFalse(vm.state.value.isLoadingRoomAccess)
        assertFalse(vm.state.value.roomAccessModified)
        assertFalse(vm.state.value.roomAccessSectionError)
        assertNull(vm.state.value.applyBanner)
        assertTrue(cli.sent.isEmpty())
    }

    @Test
    fun typingARoomGuestPasswordDoesNotRadio() = runTest {
        val sender = scriptedSender(emptyMap())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))
        runCurrent()
        val sent = sender.sent.size
        val readOnly = vm.state.value.allowReadOnly

        vm.setGuestPassword("lobby")
        runCurrent()

        assertEquals(sent, sender.sent.size)
        assertTrue(sender.sent.isEmpty())
        assertEquals("lobby", vm.state.value.guestPassword)
        assertTrue(vm.state.value.roomAccessModified)
        assertEquals(readOnly, vm.state.value.allowReadOnly)
        assertNull(vm.state.value.applyBanner)
        assertFalse(vm.state.value.roomAccessLoaded)
        assertFalse(vm.state.value.roomAccessSectionError)
    }

    @Test
    fun openingRoomDeviceActionsDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertFalse(vm.state.value.deviceActionsExpanded)

        vm.setDeviceActionsExpanded(true)
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertTrue(state.deviceActionsExpanded)
        assertFalse(state.showRebootConfirm)
        assertFalse(state.isSendingAdvert)
        assertFalse(state.isRebooting)
        assertFalse(state.isSyncingTime)
        assertNull(state.applyBanner)
    }

    @Test
    fun clearingAnEmptyRoomApplyBannerDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertNull(vm.state.value.applyBanner)
        assertFalse(vm.state.value.roomAccessLoaded)
        assertFalse(vm.state.value.deviceActionsExpanded)
        assertFalse(vm.state.value.showRebootConfirm)
        assertFalse(vm.state.value.isRebooting)
        assertFalse(vm.state.value.isSyncingTime)

        vm.clearApplyBanner()
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertNull(state.applyBanner)
        assertFalse(state.roomAccessLoaded)
        assertFalse(state.deviceActionsExpanded)
        assertFalse(state.showRebootConfirm)
        assertFalse(state.isRebooting)
        assertFalse(state.isSyncingTime)
    }

    @Test
    fun typingARoomNameDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.name)

        vm.setName("Lobby")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("Lobby", state.name)
        assertTrue(state.identityModified)
        assertEquals(null, state.identityValidation)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARoomLongitudeDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.name)
        assertFalse(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityExpanded)

        vm.setLongitudeText("-75.165")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("-75.165", state.longitudeText)
        assertEquals("", state.latitudeText)
        assertEquals("", state.name)
        assertTrue(state.identityModified)
        assertFalse(state.identityExpanded)
        assertFalse(state.identityLoaded)
        assertFalse(state.isLoadingIdentity)
        assertEquals(null, state.identityValidation)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARoomLatitudeDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("", vm.state.value.name)
        assertFalse(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityExpanded)

        vm.setLatitudeText("39.952")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("39.952", state.latitudeText)
        assertEquals("", state.longitudeText)
        assertEquals("", state.name)
        assertTrue(state.identityModified)
        assertFalse(state.identityExpanded)
        assertFalse(state.identityLoaded)
        assertFalse(state.isLoadingIdentity)
        assertEquals(null, state.identityValidation)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingARoomMapLocationDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("", vm.state.value.name)
        assertFalse(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityExpanded)

        vm.setLocationFromPicker(40.0, -75.0)
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("40.0", state.latitudeText)
        assertEquals("-75.0", state.longitudeText)
        assertEquals("", state.name)
        assertTrue(state.identityModified)
        assertFalse(state.identityExpanded)
        assertFalse(state.identityLoaded)
        assertFalse(state.isLoadingIdentity)
        assertEquals(null, state.identityValidation)
        assertFalse(state.isApplyingIdentity)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun openingRoomSecurityDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertFalse(vm.state.value.securityExpanded)

        vm.setSecurityExpanded(true)
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertTrue(state.securityExpanded)
        assertEquals("", state.newPassword)
        assertEquals("", state.confirmPassword)
        assertFalse(state.isChangingPassword)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingANewRoomPasswordDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)

        vm.setNewPassword("northdoor")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("northdoor", state.newPassword)
        assertEquals("", state.confirmPassword)
        assertFalse(state.isChangingPassword)
        assertFalse(state.securityExpanded)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARoomPasswordConfirmationDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.confirmPassword)
        assertEquals("", vm.state.value.newPassword)
        assertFalse(vm.state.value.securityExpanded)

        vm.setConfirmPassword("sidegate")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("sidegate", state.confirmPassword)
        assertEquals("", state.newPassword)
        assertFalse(state.isChangingPassword)
        assertFalse(state.securityExpanded)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARoomAdvertIntervalDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorExpanded)

        vm.setAdvertIntervalText("90")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("90", state.advertIntervalText)
        assertEquals("", state.floodAdvertIntervalText)
        assertEquals("", state.floodMaxHopsText)
        assertTrue(state.behaviorModified)
        assertFalse(state.behaviorExpanded)
        assertFalse(state.behaviorLoaded)
        assertFalse(state.isLoadingBehavior)
        assertFalse(state.isApplyingBehavior)
        assertEquals(null, state.behaviorValidation)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARoomFloodAdvertIntervalDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorExpanded)

        vm.setFloodAdvertIntervalText("12")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("", state.advertIntervalText)
        assertEquals("12", state.floodAdvertIntervalText)
        assertEquals("", state.floodMaxHopsText)
        assertTrue(state.behaviorModified)
        assertFalse(state.behaviorExpanded)
        assertFalse(state.behaviorLoaded)
        assertFalse(state.isLoadingBehavior)
        assertFalse(state.isApplyingBehavior)
        assertEquals(null, state.behaviorValidation)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARoomFloodHopLimitDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorExpanded)

        vm.setFloodMaxHopsText("4")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("", state.advertIntervalText)
        assertEquals("", state.floodAdvertIntervalText)
        assertEquals("4", state.floodMaxHopsText)
        assertTrue(state.behaviorModified)
        assertFalse(state.behaviorExpanded)
        assertFalse(state.behaviorLoaded)
        assertFalse(state.isLoadingBehavior)
        assertFalse(state.isApplyingBehavior)
        assertEquals(null, state.behaviorValidation)
        assertEquals(null, state.applyBanner)
    }
}

/**
 * Choosing a room option before the section loads must not radio.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsChoiceSilenceTest {
    @Test
    fun typingRoomOwnerInfoDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.ownerInfo)
        assertFalse(vm.state.value.ownerInfoTooLong)
        assertFalse(vm.state.value.contactInfoModified)
        assertFalse(vm.state.value.contactInfoExpanded)

        vm.setOwnerInfo("west gate")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("west gate", state.ownerInfo)
        assertFalse(state.ownerInfoTooLong)
        assertFalse(state.contactInfoModified)
        assertFalse(state.contactInfoExpanded)
        assertFalse(state.contactInfoLoaded)
        assertFalse(state.isLoadingContactInfo)
        assertFalse(state.isApplyingContactInfo)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun togglingRoomReadOnlyDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals(null, vm.state.value.allowReadOnly)
        assertEquals(null, vm.state.value.guestPassword)
        assertFalse(vm.state.value.roomAccessModified)

        vm.setAllowReadOnly(true)
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals(true, state.allowReadOnly)
        assertEquals(null, state.guestPassword)
        assertTrue(state.roomAccessModified)
        assertFalse(state.roomAccessExpanded)
        assertFalse(state.roomAccessLoaded)
        assertFalse(state.isApplyingRoomAccess)
        assertFalse(state.roomAccessSectionError)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun typingARoomFrequencyDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
        assertEquals("", vm.state.value.spreadingFactorText)
        assertEquals("", vm.state.value.codingRateText)
        assertFalse(vm.state.value.radioModified)
        assertFalse(vm.state.value.radioExpanded)

        vm.setFrequencyText("910.525")
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("910.525", state.frequencyText)
        assertEquals("", state.bandwidthText)
        assertEquals("", state.spreadingFactorText)
        assertEquals("", state.codingRateText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertFalse(state.radioLoaded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingARoomBandwidthDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.bandwidthText)
        assertEquals("", vm.state.value.frequencyText)
        assertFalse(vm.state.value.radioModified)

        vm.setBandwidth(62.5)
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("62.5", state.bandwidthText)
        assertEquals("", state.frequencyText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertFalse(state.radioLoaded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingARoomSpreadingFactorDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.spreadingFactorText)
        assertEquals("", vm.state.value.bandwidthText)
        assertEquals("", vm.state.value.frequencyText)
        assertFalse(vm.state.value.radioModified)

        vm.setSpreadingFactor(9)
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("9", state.spreadingFactorText)
        assertEquals("", state.bandwidthText)
        assertEquals("", state.frequencyText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertFalse(state.radioLoaded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }

    @Test
    fun choosingARoomCodingRateDoesNotRadio() = runTest {
        val quiet = quietRoomSettings()
        val vm = quiet.vm
        assertEquals("", vm.state.value.codingRateText)
        assertEquals("", vm.state.value.spreadingFactorText)
        assertFalse(vm.state.value.radioModified)

        vm.setCodingRate(5)
        runCurrent()

        quiet.assertStillQuiet()
        val state = vm.state.value
        assertEquals("5", state.codingRateText)
        assertEquals("", state.spreadingFactorText)
        assertTrue(state.radioModified)
        assertFalse(state.radioExpanded)
        assertFalse(state.radioLoaded)
        assertFalse(state.isApplyingRadio)
        assertEquals(null, state.applyBanner)
    }
}

/**
 * Identity, contact, and device-info loads publish the scripted reply.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsIdentityLoadTest {
    @Test
    fun identitySectionLoadsAndRenamesTheRoom() = runTest {
        val sender = scriptedSender(mapOf("get name" to "Old Room", "get lat" to "1.0", "get lon" to "2.0"))
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setIdentityExpanded(true)
        runCurrent()
        assertTrue(vm.state.value.identityLoaded)
        assertEquals("Old Room", vm.state.value.name)

        vm.setName("New Room")
        assertTrue(vm.state.value.identityModified)
        vm.applyIdentity()
        runCurrent()

        assertTrue(sender.sent.contains("set name New Room"))
        assertNull(vm.state.value.applyBanner)
        assertTrue(vm.state.value.identityApplySuccess)
        advanceTimeBy(APPLY_SUCCESS_FLASH_MS)
        runCurrent()
        assertFalse(vm.state.value.identityApplySuccess)
    }

    @Test
    fun overlongNameDoesNotSend() = runTest {
        val (nodeSession, _, vm) = connectedRoomHarness()

        val tooLong = "x".repeat(MAX_USABLE_NAME_BYTES + 1)
        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setName(tooLong)
        assertTrue(vm.state.value.identityModified)

        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore, nodeSession.sendCommandCalls.size)
        assertTrue(nodeSession.sendCommandCalls.none { it.second.contains("set name") })
        assertTrue(vm.state.value.identityValidation?.nameTooLong == true)
        assertEquals(tooLong, vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityApplySuccess)
        assertFalse(vm.state.value.isApplyingIdentity)
        assertNull(vm.state.value.applyBanner)
    }

    @Test
    fun overlongIdentityNameSurfacesOnTheFieldNotABanner() = runTest {
        val vm = RoomSettingsViewModel(backgroundScope, controller(scriptedSender(emptyMap())))

        vm.setName("x".repeat(MAX_USABLE_NAME_BYTES + 1))
        vm.applyIdentity()
        runCurrent()

        assertTrue(vm.state.value.identityValidation?.nameTooLong == true)
        assertNull(vm.state.value.applyBanner)
    }

    @Test
    fun mapPickerFillsTheCoordinateFields() = runTest {
        val vm = RoomSettingsViewModel(backgroundScope, controller(scriptedSender(emptyMap())))

        vm.setLocationFromPicker(latitude = 51.5, longitude = -0.12)

        assertEquals("51.5", vm.state.value.latitudeText)
        assertEquals("-0.12", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityModified)
    }

    @Test
    fun contactInfoSectionLoadsAndApplies() = runTest {
        val sender = scriptedSender(mapOf("get owner.info" to "Village hall"))
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setContactInfoExpanded(true)
        runCurrent()
        assertEquals("Village hall", vm.state.value.ownerInfo)

        vm.setOwnerInfo("Village hall, back room")
        vm.applyContactInfo()
        runCurrent()

        assertTrue(sender.sent.any { it.startsWith("set owner.info ") })
        assertTrue(vm.state.value.contactInfoApplySuccess)
        assertNull(vm.state.value.applyBanner)
    }

    @Test
    fun ownerInfoBeyondTheFirmwareCapIsFlagged() = runTest {
        val vm = RoomSettingsViewModel(backgroundScope, controller(scriptedSender(emptyMap())))

        vm.setOwnerInfo("x".repeat(NodeSettingsValidation.OWNER_INFO_MAX_LENGTH))
        assertFalse(vm.state.value.ownerInfoTooLong)

        vm.setOwnerInfo("x".repeat(NodeSettingsValidation.OWNER_INFO_MAX_LENGTH + 1))
        assertTrue(vm.state.value.ownerInfoTooLong)
    }

    @Test
    fun lateOwnerInfoReplyDoesNotPublishWhenTheQueryTimedOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                if (cmd == "get owner.info") throw RemoteNodeError.Timeout else "OK"
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadContactInfo()
        runCurrent()
        assertTrue(vm.state.value.contactInfoSectionError)
        assertEquals("", vm.state.value.ownerInfo)

        // get owner.info is not a machine-checked query, so a later reply cannot fill it.
        service.invokeCLIHandler(testContact(), "Village hall")
        runCurrent()

        assertEquals("", vm.state.value.ownerInfo)
        assertTrue(vm.state.value.contactInfoSectionError)
    }

    @Test
    fun deviceInfoSectionShowsFirmwareAndClock() = runTest {
        val sender = scriptedSender(mapOf("ver" to "v1.2.3", "clock" to "10:00 - 27/7/2026 UTC"))
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setDeviceInfoExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.deviceInfoLoaded)
        assertEquals("v1.2.3", vm.state.value.firmwareVersion)
        assertEquals("10:00 - 27/7/2026 UTC", vm.state.value.deviceTimeUtc)
        assertEquals(
            NodeSettingsResponseParser.utcEpochSeconds("10:00 - 27/7/2026 UTC"),
            vm.state.value.deviceTimeEpochSeconds,
        )
    }

    @Test
    fun lateClockReplyLeavesTheDeviceInfoErrorWhenFirmwareTimedOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "ver", "clock" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadDeviceInfo()
        runCurrent()
        assertTrue(vm.state.value.deviceInfoSectionError)
        assertNull(vm.state.value.firmwareVersion)
        assertNull(vm.state.value.deviceTimeUtc)

        // ver is not machine-checked. The clock reply still belongs to clock, and firmware stays missing.
        service.invokeCLIHandler(testContact(), "10:00 - 27/7/2026 UTC")
        runCurrent()

        assertEquals("10:00 - 27/7/2026 UTC", vm.state.value.deviceTimeUtc)
        assertNull(vm.state.value.firmwareVersion)
        assertTrue(vm.state.value.deviceInfoSectionError)
    }

    @Test
    fun lateClockReplyClearsTheDeviceInfoErrorWhenFirmwareIsStored() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "ver" -> "v1.2.3"
                    "clock" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadDeviceInfo()
        runCurrent()
        assertEquals("v1.2.3", vm.state.value.firmwareVersion)
        assertNull(vm.state.value.deviceTimeUtc)
        assertTrue(vm.state.value.deviceInfoSectionError)

        // Firmware is already stored, so the clock reply completes the section.
        service.invokeCLIHandler(testContact(), "10:00 - 27/7/2026 UTC")
        runCurrent()

        assertEquals("10:00 - 27/7/2026 UTC", vm.state.value.deviceTimeUtc)
        assertEquals("v1.2.3", vm.state.value.firmwareVersion)
        assertFalse(vm.state.value.deviceInfoSectionError)
    }

    @Test
    fun lateVersionReplyLeavesFirmwareEmptyWhenTheClockIsStored() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "ver" -> throw RemoteNodeError.Timeout
                    "clock" -> "10:00 - 27/7/2026 UTC"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadDeviceInfo()
        runCurrent()
        assertNull(vm.state.value.firmwareVersion)
        assertEquals("10:00 - 27/7/2026 UTC", vm.state.value.deviceTimeUtc)
        assertTrue(vm.state.value.deviceInfoLoaded)
        assertTrue(vm.state.value.deviceInfoSectionError)

        // ver is not machine-checked, so a later version reply cannot fill firmware.
        service.invokeCLIHandler(testContact(), "v1.2.3")
        runCurrent()

        assertNull(vm.state.value.firmwareVersion)
        assertEquals("10:00 - 27/7/2026 UTC", vm.state.value.deviceTimeUtc)
        assertTrue(vm.state.value.deviceInfoLoaded)
        assertTrue(vm.state.value.deviceInfoSectionError)
    }

    @Test
    fun nonClockReplyLeavesTheDeviceTimeEmpty() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "ver" -> "v1.2.3"
                    "clock" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadDeviceInfo()
        runCurrent()
        assertTrue(vm.state.value.deviceInfoSectionError)
        assertNull(vm.state.value.deviceTimeUtc)

        // A reply that is not a clock cannot fill the timed-out clock query.
        service.invokeCLIHandler(testContact(), "not a clock")
        runCurrent()

        assertNull(vm.state.value.deviceTimeUtc)
        assertEquals("v1.2.3", vm.state.value.firmwareVersion)
        assertTrue(vm.state.value.deviceInfoSectionError)
    }
}

/**
 * Radio, coordinates, and room-access loads publish only the open query.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsRadioLoadTest {
    @Test
    fun loadOpenSectionsLeavesRadioQuietUntilExpanded() = runTest {
        val sender = scriptedSender(mapOf("get radio" to "915.0,250.0,10,5"))
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))
        vm.loadOpenSections()
        runCurrent()
        assertFalse(vm.state.value.radioLoaded)
        assertEquals(0, sender.sent.size)
        vm.setRadioExpanded(true)
        runCurrent()
        assertEquals("915.0", vm.state.value.frequencyText)
        assertTrue(vm.state.value.radioLoaded)
    }

    @Test
    fun lateRadioReplyPublishesEveryFieldAndSnapsBandwidth() = runTest {
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(
            backgroundScope,
            controller(radioTimeoutSender()),
            roomAdminService = { service },
        )
        runCurrent()

        vm.loadRadio()
        runCurrent()
        assertTrue(vm.state.value.radioSectionError)

        service.invokeCLIHandler(testContact(), "915.0,7.799,10,5")
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
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                if (cmd == "get radio") throw RemoteNodeError.Timeout else "OK"
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadRadio()
        runCurrent()
        assertTrue(vm.state.value.radioSectionError)

        // A reply that is not radio settings cannot fill the timed-out radio query.
        service.invokeCLIHandler(testContact(), "not radio")
        runCurrent()

        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
        assertEquals("", vm.state.value.spreadingFactorText)
        assertEquals("", vm.state.value.codingRateText)
        assertTrue(vm.state.value.radioSectionError)
    }

    @Test
    fun lateLatitudePublishesWhenOnlyThatCoordinateIsUnanswered() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> "Hall"
                    "get lat" -> throw RemoteNodeError.Timeout
                    "get lon" -> "not-a-coordinate"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        assertEquals("Hall", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        // Only latitude is still unanswered, so 38.5 belongs to that field.
        service.invokeCLIHandler(testContact(), "38.5")
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("Hall", vm.state.value.name)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun ambiguousLateCoordinateDoesNotPublishEitherField() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> "Hall"
                    "get lat", "get lon" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        assertEquals("Hall", vm.state.value.name)
        assertTrue(vm.state.value.identitySectionError)

        // Both coordinates timed out, so a bare number belongs to neither field.
        service.invokeCLIHandler(testContact(), "38.5")
        runCurrent()

        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("Hall", vm.state.value.name)
        assertTrue(vm.state.value.identitySectionError)
    }

    @Test
    fun lateLongitudePublishesWhenOnlyThatCoordinateIsUnanswered() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> "Hall"
                    "get lat" -> "38.5"
                    "get lon" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        assertEquals("Hall", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        // Only longitude is still unanswered, so -75.1 belongs to that field.
        service.invokeCLIHandler(testContact(), "-75.1")
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertEquals("Hall", vm.state.value.name)
        assertFalse(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun lateNameReplyLeavesTheNameEmptyWhenTheCoordinatesAreStored() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> throw RemoteNodeError.Timeout
                    "get lat" -> "38.5"
                    "get lon" -> "-75.1"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        assertEquals("", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityLoaded)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)

        // get name is not machine-checked, so a later reply cannot fill the name.
        service.invokeCLIHandler(testContact(), "Hall")
        runCurrent()

        assertEquals("", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityLoaded)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun duplicateLatitudeReplyDoesNotPublishAsLongitude() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> "Hall"
                    "get lat" -> "38.5"
                    "get lon" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        assertEquals("Hall", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        // The latitude reply was already seen, so the same text cannot fill longitude.
        service.invokeCLIHandler(testContact(), "38.5")
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertEquals("Hall", vm.state.value.name)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun laterLongitudePublishesAfterARepeatedLatitudeIsIgnored() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> "Hall"
                    "get lat" -> "38.5"
                    "get lon" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        service.invokeCLIHandler(testContact(), "38.5")
        runCurrent()
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        // The repeated latitude was ignored, so longitude is still unanswered.
        service.invokeCLIHandler(testContact(), "-75.1")
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertEquals("Hall", vm.state.value.name)
        assertFalse(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun evictedLatitudePublishesAsLongitude() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> "Hall"
                    "get lat" -> "38.5"
                    "get lon" -> throw RemoteNodeError.Timeout
                    "get radio" -> "seen"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadIdentity()
        runCurrent()
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)

        // Name sits ahead of latitude, and longitude never arrived.
        // Fifteen later radio replies leave 38.5 inside the 16-reply window.
        // The sixteenth pushes it out.
        val repliesThatLeaveTheLatitudeInside = 15
        val repliesThatEvictTheLatitude = 16
        repeat(repliesThatLeaveTheLatitudeInside) {
            vm.loadRadio()
            runCurrent()
        }
        service.invokeCLIHandler(testContact(), "38.5")
        runCurrent()
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)

        vm.loadRadio()
        runCurrent()
        val radioReads = sender.sent.count { it == "get radio" }
        assertEquals(repliesThatEvictTheLatitude, radioReads)

        service.invokeCLIHandler(testContact(), "38.5")
        runCurrent()

        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("38.5", vm.state.value.longitudeText)
        assertEquals("Hall", vm.state.value.name)
        assertFalse(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun cleanupCancelledBeforeTheClearRunsLeavesTheScreenBlank() = runTest {
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        // A child scope stands in for the composition scope, which is cancelled in the same leave as cleanup.
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val vm = RoomSettingsViewModel(scope, controller(radioTimeoutSender()), roomAdminService = { service })
        runCurrent()

        vm.loadRadio()
        runCurrent()
        assertTrue(vm.state.value.radioSectionError)

        vm.cleanup()
        scope.coroutineContext[Job]?.cancel()
        runCurrent()

        service.invokeCLIHandler(testContact(), "915.0,7.799,10,5")
        runCurrent()

        assertEquals("", vm.state.value.frequencyText)
        assertEquals("", vm.state.value.bandwidthText)
        assertTrue(vm.state.value.radioSectionError)
    }

    @Test
    fun cleanupClearsTheServiceWhereTheHandlerWasRegistered() = runTest {
        val original = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val replacement = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        var current = original
        val vm = RoomSettingsViewModel(
            backgroundScope,
            controller(scriptedSender(emptyMap())),
            roomAdminService = { current },
        )
        runCurrent()

        var replacementCalls = 0
        replacement.setCLIHandler { _, _ -> replacementCalls += 1 }
        current = replacement

        vm.cleanup()
        runCurrent()
        replacement.invokeCLIHandler(testContact(), "replacement")

        assertEquals(1, replacementCalls)
    }

    @Test
    fun cleanupRejectsLaterDeviceActions() = runTest {
        val sender = scriptedSender(emptyMap())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.cleanup()
        vm.sendAdvert()
        vm.syncTime()
        vm.confirmReboot()
        runCurrent()

        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun radioSectionLoadsAndApplies() = runTest {
        val sender = scriptedSender(mapOf("get radio" to "915.0,250.0,10,5"))
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setRadioExpanded(true)
        runCurrent()
        assertEquals("915.0", vm.state.value.frequencyText)
        assertEquals("250", vm.state.value.bandwidthText)

        vm.setFrequencyText("869.5")
        vm.setBandwidth(125.0)
        vm.applyRadio()
        runCurrent()

        assertTrue(sender.sent.any { it.startsWith("set radio 869.5,") })
        assertEquals("125", vm.state.value.bandwidthText)
    }

    @Test
    fun radioPickerWritesOnGridBandwidthSpreadingAndCoding() = runTest {
        val sender = scriptedSender(mapOf("get radio" to "915.0,250.0,10,5"))
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setRadioExpanded(true)
        runCurrent()
        vm.setBandwidth(62.5)
        vm.setSpreadingFactor(7)
        vm.setCodingRate(8)

        assertEquals("62.5", vm.state.value.bandwidthText)
        assertEquals("7", vm.state.value.spreadingFactorText)
        assertEquals("8", vm.state.value.codingRateText)
        assertTrue(vm.state.value.radioModified)
    }

    @Test
    fun securitySectionChangesTheAdminPassword() = runTest {
        val sender = scriptedSender(emptyMap())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setNewPassword("hunter2")
        vm.setConfirmPassword("hunter2")
        vm.changePassword()
        runCurrent()

        assertTrue(sender.sent.contains("password hunter2"))
        assertNull(vm.state.value.applyBanner)
        assertTrue(vm.state.value.changePasswordSuccess)
        advanceTimeBy(APPLY_SUCCESS_FLASH_MS)
        runCurrent()
        assertFalse(vm.state.value.changePasswordSuccess)
    }

    @Test
    fun deviceActionsSendAdvertSyncTimeAndReboot() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when {
                    cmd.startsWith(RemoteCLICommandRewriter.TIME_COMMAND_PREFIX) -> "OK - time updated"
                    cmd == "reboot" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.sendAdvert()
        runCurrent()
        assertEquals(ApplyBanner.AdvertSent, vm.state.value.applyBanner)

        vm.syncTime()
        runCurrent()
        assertEquals(ApplyBanner.TimeSynced, vm.state.value.applyBanner)

        vm.setShowRebootConfirm(true)
        vm.confirmReboot()
        runCurrent()
        assertFalse(vm.state.value.showRebootConfirm)
        // A reboot answers with silence, so the expected timeout reports as sent.
        assertEquals(ApplyBanner.RebootSent, vm.state.value.applyBanner)
        assertTrue(sender.sent.contains("advert"))
        assertTrue(sender.sent.contains("reboot"))
    }

    @Test
    fun roomAccessApplyFlashesSuccessInsteadOfABanner() = runTest {
        val sender = scriptedSender(
            mapOf("get guest.password" to "> old", "get allow.read.only" to "off"),
        )
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setRoomAccessExpanded(true)
        runCurrent()
        vm.setGuestPassword("new")
        vm.applyRoomAccess()
        runCurrent()

        assertTrue(sender.sent.contains("set guest.password new"))
        assertNull(vm.state.value.applyBanner)
        assertTrue(vm.state.value.roomAccessApplySuccess)
        advanceTimeBy(APPLY_SUCCESS_FLASH_MS)
        runCurrent()
        assertFalse(vm.state.value.roomAccessApplySuccess)
    }
}

/**
 * A late access or coordinate reply fills only the query still open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsAccessReplyTest {
    @Test
    fun aPartialRoomAccessTimeoutLeavesTheFailedFieldUnloaded() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get guest.password" -> "> secret"
                    "get allow.read.only" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setRoomAccessExpanded(true)
        runCurrent()

        assertTrue(vm.state.value.roomAccessLoaded)
        assertEquals("secret", vm.state.value.guestPassword)
        assertNull(vm.state.value.allowReadOnly)
        assertTrue(vm.state.value.roomAccessSectionError)
        assertFalse(roomAccessFieldEnabled(valueLoaded = false, isApplying = false))
    }

    @Test
    fun lateGuestPasswordReplyDoesNotPublishWhenTheQueryTimedOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get guest.password" -> throw RemoteNodeError.Timeout
                    "get allow.read.only" -> "off"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadRoomAccess()
        runCurrent()
        assertTrue(vm.state.value.roomAccessSectionError)
        assertNull(vm.state.value.guestPassword)

        // get guest.password is not a machine-checked query, so a later reply cannot fill it.
        service.invokeCLIHandler(testContact(), "lobby")
        runCurrent()

        assertNull(vm.state.value.guestPassword)
        assertTrue(vm.state.value.roomAccessSectionError)
    }

    @Test
    fun lateReadOnlyReplyDoesNotPublishWhenTheQueryTimedOut() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get guest.password" -> "> secret"
                    "get allow.read.only" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadRoomAccess()
        runCurrent()
        assertTrue(vm.state.value.roomAccessSectionError)
        assertEquals("secret", vm.state.value.guestPassword)
        assertNull(vm.state.value.allowReadOnly)

        // get allow.read.only is not a machine-checked query, so a later reply cannot fill it.
        service.invokeCLIHandler(testContact(), "on")
        runCurrent()

        assertNull(vm.state.value.allowReadOnly)
        assertEquals("secret", vm.state.value.guestPassword)
        assertTrue(vm.state.value.roomAccessSectionError)
    }

    @Test
    fun behaviorApplyFlashesSuccessInsteadOfABanner() = runTest {
        val sender = scriptedSender(
            mapOf(
                "get advert.interval" to "120",
                "get flood.advert.interval" to "24",
                "get flood.max" to "8",
            ),
        )
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender))

        vm.setBehaviorExpanded(true)
        runCurrent()
        vm.setFloodMaxHopsText("10")
        vm.applyBehavior()
        runCurrent()

        assertTrue(sender.sent.contains("set flood.max 10"))
        assertNull(vm.state.value.applyBanner)
        assertTrue(vm.state.value.behaviorApplySuccess)
        advanceTimeBy(APPLY_SUCCESS_FLASH_MS)
        runCurrent()
        assertFalse(vm.state.value.behaviorApplySuccess)
    }

    @Test
    fun lateAdvertIntervalPublishesWhenItIsTheOnlyUnansweredBehaviorQuery() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> throw RemoteNodeError.Timeout
                    "get flood.advert.interval" -> "2"
                    "get flood.max" -> "3"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertTrue(vm.state.value.behaviorSectionError)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)

        // 120 parses as every behavior integer. Only the advert interval is still unanswered.
        service.invokeCLIHandler(testContact(), "120")
        runCurrent()

        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun repeatedFloodMaxDoesNotPublishAsAdvertInterval() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> throw RemoteNodeError.Timeout
                    "get flood.advert.interval" -> "2"
                    "get flood.max" -> "3"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        // 3 was the flood max reply, so the same text cannot fill the advert interval.
        service.invokeCLIHandler(testContact(), "3")
        runCurrent()

        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun timelyFloodAdvertReplyDoesNotPublishAsAdvertInterval() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.loadBehavior()
        runCurrent()

        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        answerCli(nodeSession, session.publicKey, "get flood.advert.interval", "2")
        answerCli(nodeSession, session.publicKey, "get flood.max", "3")

        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        // 3 was the flood max reply, so the same text cannot fill the advert interval.
        nodeSession.emitCliResponse("3", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun timelyLongitudeDoesNotPublishAsLatitude() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.loadIdentity()
        runCurrent()

        answerCli(nodeSession, session.publicKey, "get name", "Hall")
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        answerCli(nodeSession, session.publicKey, "get lon", "-75.1")

        assertEquals("Hall", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("-75.1", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun stalePrefixedLatitudeStillFillsAfterTheWaiterDropsIt() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.loadIdentity()
        runCurrent()

        answerCli(nodeSession, session.publicKey, "get name", "Hall")
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()

        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("get lon"))
        val wirePrefix = command.removeSuffix("get lon")
        val stalePrefix = if (wirePrefix == "00|") "FF|" else "00|"
        nodeSession.emitCliResponse("${stalePrefix}38.5", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Hall", vm.state.value.name)
        assertEquals("38.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identitySectionError)
        assertFalse(vm.state.value.identityModified)
    }

    @Test
    fun timelyGuestPasswordDoesNotPublishAsAdvertInterval() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.loadBehavior()
        runCurrent()

        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()
        answerCli(nodeSession, session.publicKey, "get flood.advert.interval", "2")
        answerCli(nodeSession, session.publicKey, "get flood.max", "3")
        assertEquals("", vm.state.value.advertIntervalText)

        vm.loadRoomAccess()
        runCurrent()
        answerCli(nodeSession, session.publicKey, "get guest.password", "120")
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()

        assertEquals("120", vm.state.value.guestPassword)
        assertEquals("", vm.state.value.advertIntervalText)
        assertNull(vm.state.value.allowReadOnly)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
    }

    @Test
    fun okGuestPasswordPublishesEmpty() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.loadRoomAccess()
        runCurrent()

        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("get guest.password"))
        val wirePrefix = command.removeSuffix("get guest.password")
        nodeSession.emitCliResponse("${wirePrefix}OK", session.publicKey.copyOf(6))
        runCurrent()
        advanceTimeBy(CLI_TIMEOUT_STEP)
        runCurrent()

        assertEquals("", vm.state.value.guestPassword)
        assertNull(vm.state.value.allowReadOnly)
        assertTrue(vm.state.value.roomAccessSectionError)
    }

    @Test
    fun readOnlyOnPublishesAfterAnEmptyGuestPassword() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.loadRoomAccess()
        runCurrent()
        answerCli(nodeSession, session.publicKey, "get guest.password", "OK")
        answerCli(nodeSession, session.publicKey, "get allow.read.only", "on")

        assertEquals("", vm.state.value.guestPassword)
        assertEquals(true, vm.state.value.allowReadOnly)
        assertFalse(vm.state.value.roomAccessSectionError)
    }

    @Test
    fun setGuestPasswordPublishesSuccess() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.setGuestPassword("lobby")
        vm.applyRoomAccess()
        runCurrent()
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set guest.password lobby"))
        val prefix = command.removeSuffix("set guest.password lobby")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("lobby", vm.state.value.guestPassword)
        assertFalse(vm.state.value.roomAccessModified)
        assertTrue(vm.state.value.roomAccessApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }
}

/**
 * Read-only, name, radio, and clock commands send the matching room command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsCommandTest {
    @Test
    fun enabledReadOnlySendsTheSetCommand() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setAllowReadOnly(true)
        vm.applyRoomAccess()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set allow.read.only on"))
        val prefix = command.removeSuffix("set allow.read.only on")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(true, vm.state.value.allowReadOnly)
        assertEquals(null, vm.state.value.guestPassword)
        assertFalse(vm.state.value.roomAccessModified)
        assertTrue(vm.state.value.roomAccessApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun disabledReadOnlySendsTheSetCommand() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setAllowReadOnly(false)
        vm.applyRoomAccess()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set allow.read.only off"))
        val prefix = command.removeSuffix("set allow.read.only off")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(false, vm.state.value.allowReadOnly)
        assertEquals(null, vm.state.value.guestPassword)
        assertFalse(vm.state.value.roomAccessModified)
        assertTrue(vm.state.value.roomAccessApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun setFloodMaxPublishesSuccess() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.setFloodMaxHopsText("10")
        vm.applyBehavior()
        runCurrent()
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set flood.max 10"))
        val prefix = command.removeSuffix("set flood.max 10")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("10", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun setFloodAdvertIntervalPublishesSuccess() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setFloodAdvertIntervalText("6")
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set flood.advert.interval 6"))
        val prefix = command.removeSuffix("set flood.advert.interval 6")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("6", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun renameRoomPublishesTheNewName() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setName("Lobby")
        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set name Lobby"))
        val prefix = command.removeSuffix("set name Lobby")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Lobby", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertFalse(vm.state.value.identityModified)
        assertTrue(vm.state.value.identityApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedRenameKeepsTheTypedName() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setName("Lobby")
        assertTrue(vm.state.value.identityModified)
        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set name Lobby"))
        val prefix = command.removeSuffix("set name Lobby")
        nodeSession.emitCliResponse("${prefix}ERR: denied", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Lobby", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.longitudeText)
        assertTrue(vm.state.value.identityModified)
        assertFalse(vm.state.value.identityApplySuccess)
        assertFalse(vm.state.value.isApplyingIdentity)
        assertEquals(ApplyBanner.PartialFailure, vm.state.value.applyBanner)
    }

    @Test
    fun setOwnerInfoPublishesTheText() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setOwnerInfo("Village hall")
        vm.applyContactInfo()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set owner.info Village hall"))
        val prefix = command.removeSuffix("set owner.info Village hall")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Village hall", vm.state.value.ownerInfo)
        assertFalse(vm.state.value.contactInfoModified)
        assertTrue(vm.state.value.contactInfoApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedOwnerInfoKeepsTheTypedText() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.loadContactInfo()
        runCurrent()
        val getCommand = nodeSession.sendCommandCalls.last().second
        assertTrue(getCommand.endsWith("get owner.info"))
        val getPrefix = getCommand.removeSuffix("get owner.info")
        nodeSession.emitCliResponse("${getPrefix}shed", session.publicKey.copyOf(6))
        runCurrent()
        assertEquals("shed", vm.state.value.ownerInfo)
        assertTrue(vm.state.value.contactInfoLoaded)
        assertFalse(vm.state.value.contactInfoModified)

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setOwnerInfo("Village hall")
        assertTrue(vm.state.value.contactInfoModified)
        vm.applyContactInfo()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set owner.info Village hall"))
        val prefix = command.removeSuffix("set owner.info Village hall")
        nodeSession.emitCliResponse("${prefix}ERR: denied", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("Village hall", vm.state.value.ownerInfo)
        assertTrue(vm.state.value.contactInfoModified)
        assertFalse(vm.state.value.contactInfoApplySuccess)
        assertFalse(vm.state.value.isApplyingContactInfo)
        assertEquals(ApplyBanner.PartialFailure, vm.state.value.applyBanner)
    }

    @Test
    fun changePasswordClearsBothFields() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setNewPassword("gatekey")
        vm.setConfirmPassword("gatekey")
        vm.changePassword()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("password gatekey"))
        val prefix = command.removeSuffix("password gatekey")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)
        assertTrue(vm.state.value.changePasswordSuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun applyRadioPublishesTheChosenParameters() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setFrequencyText("869.5")
        vm.setBandwidth(125.0)
        vm.setSpreadingFactor(9)
        vm.setCodingRate(5)
        vm.applyRadio()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set radio 869.5,125.0,9,5"))
        val prefix = command.removeSuffix("set radio 869.5,125.0,9,5")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("869.5", vm.state.value.frequencyText)
        assertEquals("125", vm.state.value.bandwidthText)
        assertEquals("9", vm.state.value.spreadingFactorText)
        assertEquals("5", vm.state.value.codingRateText)
        assertFalse(vm.state.value.radioModified)
        assertEquals(ApplyBanner.RadioApplied, vm.state.value.applyBanner)
    }

    @Test
    fun rejectedRadioApplyDoesNotShowTheRestartNotice() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setFrequencyText("869.5")
        vm.setBandwidth(125.0)
        vm.setSpreadingFactor(9)
        vm.setCodingRate(5)
        vm.applyRadio()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set radio 869.5,125.0,9,5"))
        val prefix = command.removeSuffix("set radio 869.5,125.0,9,5")
        nodeSession.emitCliResponse("${prefix}ERR: denied", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("869.5", vm.state.value.frequencyText)
        assertEquals("125", vm.state.value.bandwidthText)
        assertEquals("9", vm.state.value.spreadingFactorText)
        assertEquals("5", vm.state.value.codingRateText)
        assertTrue(vm.state.value.radioModified)
        assertFalse(vm.state.value.isApplyingRadio)
        assertEquals(ApplyBanner.RadioApplyFailed, vm.state.value.applyBanner)
    }

    @Test
    fun syncTimeSendsTheHostEpoch() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.syncTime()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        val marker = "time "
        assertTrue(command.contains(marker))
        assertFalse(command.contains("clock sync"))
        val epoch = command.substringAfterLast(marker)
        assertTrue(epoch.isNotEmpty() && epoch.all { it.isDigit() })
        val prefix = command.removeSuffix(marker + epoch)
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.TimeSynced, vm.state.value.applyBanner)
    }
}

/**
 * Advert, reboot, and coordinate applies publish the scripted result.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsApplyResultTest {
    @Test
    fun clockAheadDoesNotPublishSynced() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.syncTime()
        runCurrent()
        assertTrue(vm.state.value.isSyncingTime)
        val command = nodeSession.sendCommandCalls.last().second
        val marker = "time "
        assertTrue(command.contains(marker))
        val epoch = command.substringAfterLast(marker)
        val prefix = command.removeSuffix(marker + epoch)
        nodeSession.emitCliResponse("${prefix}ERR: clock cannot go backwards", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.ClockAhead, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSyncingTime)
        assertEquals(null, vm.state.value.deviceTimeUtc)
        assertEquals(null, vm.state.value.clockDriftSeconds)
    }

    @Test
    fun clockErrorShowsTheFirmwareText() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        vm.syncTime()
        runCurrent()
        assertTrue(vm.state.value.isSyncingTime)
        val command = nodeSession.sendCommandCalls.last().second
        val marker = "time "
        assertTrue(command.contains(marker))
        assertFalse(command.contains("clock sync"))
        val epoch = command.substringAfterLast(marker)
        val prefix = command.removeSuffix(marker + epoch)
        nodeSession.emitCliResponse("${prefix}ERR: denied", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.FirmwareError("denied"), vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSyncingTime)
        assertEquals(null, vm.state.value.deviceTimeUtc)
        assertEquals(null, vm.state.value.clockDriftSeconds)
    }

    @Test
    fun sendAdvertPublishesAdvertSent() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.sendAdvert()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("advert"))
        val prefix = command.removeSuffix("advert")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.AdvertSent, vm.state.value.applyBanner)
    }

    @Test
    fun deniedAdvertStillShowsTheSentNotice() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.sendAdvert()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("advert"))
        val prefix = command.removeSuffix("advert")
        nodeSession.emitCliResponse("${prefix}ERR: denied", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(ApplyBanner.AdvertSent, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isSendingAdvert)
    }

    @Test
    fun dismissedRebootConfirmDoesNotSend() = runTest {
        val (nodeSession, _, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setShowRebootConfirm(true)
        assertTrue(vm.state.value.showRebootConfirm)

        vm.setShowRebootConfirm(false)
        runCurrent()

        assertEquals(sentBefore, nodeSession.sendCommandCalls.size)
        assertFalse(vm.state.value.showRebootConfirm)
        assertFalse(vm.state.value.isRebooting)
        assertTrue(nodeSession.sendCommandCalls.none { it.second.endsWith("reboot") })
    }

    @Test
    fun silentRebootStillShowsSent() = runTest {
        val (nodeSession, _, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setShowRebootConfirm(true)
        vm.confirmReboot()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("reboot"))
        assertFalse(vm.state.value.showRebootConfirm)
        assertTrue(vm.state.value.isRebooting)

        advanceTimeBy(3.seconds)
        runCurrent()

        assertEquals(ApplyBanner.RebootSent, vm.state.value.applyBanner)
        assertFalse(vm.state.value.isRebooting)
    }

    @Test
    fun setLatitudePublishesTheDegreeValue() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setLatitudeText("51.5")
        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set lat 51.5"))
        val prefix = command.removeSuffix("set lat 51.5")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("51.5", vm.state.value.latitudeText)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.longitudeText)
        assertFalse(vm.state.value.identityModified)
        assertTrue(vm.state.value.identityApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun setLongitudePublishesTheDegreeValue() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setLongitudeText("-0.1")
        vm.applyIdentity()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set lon -0.1"))
        val prefix = command.removeSuffix("set lon -0.1")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("-0.1", vm.state.value.longitudeText)
        assertEquals("", vm.state.value.name)
        assertEquals("", vm.state.value.latitudeText)
        assertFalse(vm.state.value.identityModified)
        assertTrue(vm.state.value.identityApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun setAdvertIntervalPublishesSuccess() = runTest {
        val (nodeSession, session, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setAdvertIntervalText("90")
        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore + 1, nodeSession.sendCommandCalls.size)
        val command = nodeSession.sendCommandCalls.last().second
        assertTrue(command.endsWith("set advert.interval 90"))
        val prefix = command.removeSuffix("set advert.interval 90")
        nodeSession.emitCliResponse("${prefix}OK", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals("90", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorModified)
        assertTrue(vm.state.value.behaviorApplySuccess)
        assertEquals(null, vm.state.value.applyBanner)
    }

    @Test
    fun mismatchedPasswordDoesNotSend() = runTest {
        val (nodeSession, _, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setNewPassword("gatekey")
        vm.setConfirmPassword("otherkey")
        vm.changePassword()
        runCurrent()

        assertEquals(sentBefore, nodeSession.sendCommandCalls.size)
        assertEquals("gatekey", vm.state.value.newPassword)
        assertEquals("otherkey", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.changePasswordSuccess)
        assertEquals(ApplyBanner.PasswordMismatch, vm.state.value.applyBanner)
    }

    @Test
    fun emptyPasswordDoesNotSend() = runTest {
        val (nodeSession, _, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.changePassword()
        runCurrent()

        assertEquals(sentBefore, nodeSession.sendCommandCalls.size)
        assertEquals("", vm.state.value.newPassword)
        assertEquals("", vm.state.value.confirmPassword)
        assertFalse(vm.state.value.changePasswordSuccess)
        assertEquals(ApplyBanner.PasswordEmpty, vm.state.value.applyBanner)
    }

    @Test
    fun ambiguousLateIntervalDoesNotPublishAnyBehaviorField() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.Timeout } }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertTrue(vm.state.value.behaviorSectionError)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)

        // 120 parses as every behavior integer, so it belongs to none of them.
        service.invokeCLIHandler(testContact(), "120")
        runCurrent()

        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun lateFloodMaxPublishesWhenItIsTheOnlyUnansweredBehaviorQuery() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "2"
                    "get flood.max" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertTrue(vm.state.value.behaviorSectionError)
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)

        // 4 parses as every behavior integer. Only flood max is still unanswered.
        service.invokeCLIHandler(testContact(), "4")
        runCurrent()

        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("4", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }
}

/**
 * A repeated behavior reply and an out-of-range value do not send the wrong command.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomSettingsBehaviorEdgeTest {
    @Test
    fun repeatedAdvertIntervalDoesNotPublishAsFloodMax() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "2"
                    "get flood.max" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        // 120 was the advert reply, so the same text cannot fill flood max.
        service.invokeCLIHandler(testContact(), "120")
        runCurrent()

        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun laterFloodMaxPublishesAfterARepeatedAdvertIntervalIsIgnored() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "2"
                    "get flood.max" -> throw RemoteNodeError.Timeout
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        service.invokeCLIHandler(testContact(), "120")
        runCurrent()
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        // The repeated advert was ignored, so flood max is still unanswered.
        service.invokeCLIHandler(testContact(), "4")
        runCurrent()

        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("4", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun evictedAdvertIntervalPublishesAsFloodMax() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> "2"
                    "get flood.max" -> throw RemoteNodeError.Timeout
                    "get radio" -> "seen"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)

        // The window holds 16 replies. Advert and flood advert already occupy two,
        // so fourteen later radio replies leave 120 inside and the fifteenth pushes it out.
        val repliesThatLeaveTheAdvertInside = 14
        val repliesThatEvictTheAdvert = 15
        repeat(repliesThatLeaveTheAdvertInside) {
            vm.loadRadio()
            runCurrent()
        }
        service.invokeCLIHandler(testContact(), "120")
        runCurrent()
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorSectionError)

        vm.loadRadio()
        runCurrent()
        assertEquals(repliesThatEvictTheAdvert, sender.sent.count { it == "get radio" })

        service.invokeCLIHandler(testContact(), "120")
        runCurrent()

        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("2", vm.state.value.floodAdvertIntervalText)
        assertEquals("120", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun lateFloodAdvertIntervalPublishesWhenItIsTheOnlyUnansweredBehaviorQuery() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get advert.interval" -> "120"
                    "get flood.advert.interval" -> throw RemoteNodeError.Timeout
                    "get flood.max" -> "3"
                    else -> "OK"
                }
            }
        }
        val service = RoomAdminService(FakeRoomAdminSession(), InMemoryRoomStore())
        val vm = RoomSettingsViewModel(backgroundScope, controller(sender), roomAdminService = { service })
        runCurrent()

        vm.loadBehavior()
        runCurrent()
        assertTrue(vm.state.value.behaviorSectionError)
        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)

        // 12 parses as every behavior integer. Only the flood advert interval is still unanswered.
        service.invokeCLIHandler(testContact(), "12")
        runCurrent()

        assertEquals("120", vm.state.value.advertIntervalText)
        assertEquals("12", vm.state.value.floodAdvertIntervalText)
        assertEquals("3", vm.state.value.floodMaxHopsText)
        assertFalse(vm.state.value.behaviorSectionError)
        assertFalse(vm.state.value.behaviorModified)
    }

    @Test
    fun outOfRangeAdvertIntervalDoesNotSend() = runTest {
        val (nodeSession, _, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setAdvertIntervalText("1")
        assertTrue(vm.state.value.behaviorModified)

        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore, nodeSession.sendCommandCalls.size)
        assertTrue(nodeSession.sendCommandCalls.none { it.second.contains("set advert.interval") })
        assertTrue(vm.state.value.behaviorValidation?.advertIntervalInvalid == true)
        assertEquals("1", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertEquals("", vm.state.value.floodMaxHopsText)
        assertTrue(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorApplySuccess)
        assertFalse(vm.state.value.isApplyingBehavior)
        assertNull(vm.state.value.applyBanner)
    }

    @Test
    fun outOfRangeFloodMaxDoesNotSend() = runTest {
        val (nodeSession, _, vm) = connectedRoomHarness()

        val sentBefore = nodeSession.sendCommandCalls.size
        vm.setFloodMaxHopsText("65")
        assertTrue(vm.state.value.behaviorModified)

        vm.applyBehavior()
        runCurrent()

        assertEquals(sentBefore, nodeSession.sendCommandCalls.size)
        assertTrue(nodeSession.sendCommandCalls.none { it.second.contains("set flood.max") })
        assertTrue(vm.state.value.behaviorValidation?.floodMaxHopsInvalid == true)
        assertEquals("65", vm.state.value.floodMaxHopsText)
        assertEquals("", vm.state.value.advertIntervalText)
        assertEquals("", vm.state.value.floodAdvertIntervalText)
        assertTrue(vm.state.value.behaviorModified)
        assertFalse(vm.state.value.behaviorApplySuccess)
        assertFalse(vm.state.value.isApplyingBehavior)
        assertNull(vm.state.value.applyBanner)
    }

    @Test
    fun outOfRangeAdvertIntervalSurfacesOnTheFieldNotABanner() = runTest {
        val vm = RoomSettingsViewModel(backgroundScope, controller(scriptedSender(emptyMap())))

        vm.setAdvertIntervalText("1")
        vm.applyBehavior()
        runCurrent()

        assertTrue(vm.state.value.behaviorValidation?.advertIntervalInvalid == true)
        assertNull(vm.state.value.applyBanner)
    }
}

/** A connected room admin session over fakes: what an open settings sheet sees. */
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.connectedRoomHarness(): RoomHarness {
    val nodeSession = FakeNodeSession()
    val nodeStore = InMemoryRemoteNodeStore()
    val session = roomSession(permissionLevel = RoomPermissionLevel.ADMIN)
    nodeStore.saveSession(session)
    nodeStore.setContact(session.radioID, roomContact(publicKey = session.publicKey))
    nodeStore.setContactRouting(session.radioID, session.publicKey, isFloodRouted = true)
    val cli = NodeCliCoordinator(nodeSession, nodeStore, InMemoryNodePasswordStore(), backgroundScope)
    val roomStore = InMemoryRoomStore().apply { seedSession(session) }
    val service = RoomAdminService(CoordinatorRoomAdminSession(cli, session.id), roomStore, cli)
    val send: suspend (String, Duration) -> String = { command, timeout ->
        service.sendCommand(session.id, command, timeout)
    }
    val settings = RoomSettingsController(NodeSettingsController(send, send), send, send)
    val vm = RoomSettingsViewModel(backgroundScope, settings, roomAdminService = { service })
    runCurrent()
    return RoomHarness(nodeSession, session, vm)
}

private data class RoomHarness(
    val nodeSession: FakeNodeSession,
    val session: RemoteNodeSessionDto,
    val vm: RoomSettingsViewModel,
)

/** Room settings with no admin service. Opening the sheet may read the service; a later edit must not send. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.quietRoomSettings(): QuietRoomSettings {
    val sender = scriptedSender(emptyMap())
    var serviceReads = 0
    val vm = RoomSettingsViewModel(
        backgroundScope,
        controller(sender),
        roomAdminService = {
            serviceReads += 1
            null
        },
    )
    runCurrent()
    val serviceAfterInit = serviceReads
    assertTrue(serviceAfterInit > 0)
    assertTrue(sender.sent.isEmpty())
    return QuietRoomSettings(sender, vm, serviceAfterInit) { serviceReads }
}

private class QuietRoomSettings(
    private val sender: ScriptedCliSender,
    val vm: RoomSettingsViewModel,
    private val serviceAfterInit: Int,
    private val serviceReads: () -> Int,
) {
    fun assertStillQuiet() {
        assertEquals(serviceAfterInit, serviceReads())
        assertTrue(sender.sent.isEmpty())
    }
}

private fun controller(sender: ScriptedCliSender): RoomSettingsController =
    RoomSettingsController(NodeSettingsController(sender.send, sender.send), sender.send, sender.send)

private fun scriptedSender(responses: Map<String, String>): ScriptedCliSender =
    ScriptedCliSender().apply { respond = { cmd -> responses[cmd] ?: "OK" } }

/** `get radio` times out so the late-reply path is the only way the field can fill. */
private fun radioTimeoutSender(): ScriptedCliSender = ScriptedCliSender().apply {
    respond = { cmd -> if (cmd == "get radio") throw RemoteNodeError.Timeout else "OK" }
}

/** Room CLI through [NodeCliCoordinator], so fan-out and the in-flight waiter share one reply. */
private class CoordinatorRoomAdminSession(private val cli: NodeCliCoordinator, private val sessionId: Uuid) :
    RoomAdminSession {
    override suspend fun requestStatus(publicKey: ByteArray, timeout: Duration): StatusResponse = error("unused")

    override suspend fun requestTelemetry(publicKey: ByteArray, timeout: Duration): TelemetryResponse = error("unused")

    override suspend fun sendCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
        cli.sendCLICommand(sessionId, command, timeout)

    override suspend fun sendRawCommand(publicKey: ByteArray, command: String, timeout: Duration): String =
        cli.sendRawCLICommand(sessionId, command, timeout)
}

private val CLI_TIMEOUT_STEP = 11.seconds
