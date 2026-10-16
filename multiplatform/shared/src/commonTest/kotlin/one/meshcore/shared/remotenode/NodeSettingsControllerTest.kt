package one.meshcore.shared.remotenode

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Fetch/parse, apply-only-changed diffing, validation gating, and late-response recovery. */
class NodeSettingsControllerTest {
    private fun controller(sender: ScriptedCliSender) = NodeSettingsController(sender.send, sender.send)

    @Test
    fun fetchIdentityStoresValuesAndOriginals() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get name" -> "OldName"
                    "get lat" -> "1.0"
                    "get lon" -> "2.0"
                    else -> "OK"
                }
            }
        }
        val controller = controller(sender)
        controller.fetchIdentity()

        assertEquals("OldName", controller.name)
        assertEquals("OldName", controller.originalName)
        assertEquals(1.0, controller.originalLatitude)
        assertEquals(2.0, controller.originalLongitude)
        assertTrue(controller.identityLoaded)
        assertFalse(controller.identityError)
        assertFalse(controller.identitySettingsModified)
    }

    @Test
    fun fetchIdentityTimeoutSetsErrorFlag() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd -> if (cmd == "get lat") throw RemoteNodeError.Timeout else "OK-ignored" }
        }
        val controller = controller(sender)
        controller.fetchIdentity()
        assertTrue(controller.identityError)
    }

    @Test
    fun applyIdentitySendsOnlyChangedFieldsAndUpdatesOriginals() = runTest {
        val getResponses = mapOf("get name" to "OldName", "get lat" to "1.0", "get lon" to "2.0")
        val sender = ScriptedCliSender().apply { respond = { cmd -> getResponses[cmd] ?: "OK" } }
        val controller = controller(sender)
        controller.fetchIdentity()
        sender.sent.clear()

        controller.name = "NewName"
        // latitude left at its fetched 1.0 (unchanged); longitude changed.
        controller.longitude = 3.0
        val result = controller.applyIdentitySettings()

        assertIs<SettingsApplyResult.Applied>(result)
        assertContentEquals(listOf("set name NewName", "set lon 3.0"), sender.setCommands)
        assertEquals("NewName", controller.originalName)
        assertEquals(3.0, controller.originalLongitude)
    }

    @Test
    fun applyIdentityValidationFailureSendsNothing() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        controller.name = "a".repeat(40)
        val result = controller.applyIdentitySettings()
        assertIs<SettingsApplyResult.InvalidFields>(result)
        assertTrue(controller.identityValidation!!.nameTooLong)
        assertTrue(sender.sent.isEmpty())
    }

    @Test
    fun applyIdentityPartialFailureWhenCommandNotOk() = runTest {
        val sender = ScriptedCliSender().apply { respond = { cmd -> if (cmd.startsWith("set")) "ERR: nope" else "OK" } }
        val controller = controller(sender)
        controller.name = "NewName"
        val result = controller.applyIdentitySettings()
        assertIs<SettingsApplyResult.PartialFailure>(result)
        // Original is not advanced on a non-OK response.
        assertEquals(null, controller.originalName)
    }

    @Test
    fun applyRadioRequiresAllFieldsLoaded() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        assertIs<SettingsApplyResult.NotLoaded>(controller.applyRadioSettings())
    }

    @Test
    fun applyRadioSendsSingleRadioCommandAndClearsModified() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender).apply {
            frequency = 915.0
            bandwidth = 250.0
            spreadingFactor = 10
            codingRate = 5
            radioSettingsModified = true
        }
        val result = controller.applyRadioSettings()
        assertIs<SettingsApplyResult.Applied>(result)
        assertContentEquals(listOf("set radio 915.0,250.0,10,5"), sender.setCommands)
        assertFalse(controller.radioSettingsModified)
    }

    @Test
    fun applyRadioSendsFirmwareReportedValuesEvenWhenOffThePickerGrid() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender).apply {
            frequency = 915.0
            bandwidth = 137.0
            spreadingFactor = 13
            codingRate = 4
            radioSettingsModified = true
        }
        val result = controller.applyRadioSettings()
        assertIs<SettingsApplyResult.Applied>(result)
        assertContentEquals(listOf("set radio 915.0,137.0,13,4"), sender.setCommands)
        assertFalse(controller.radioSettingsModified)
    }

    @Test
    fun fetchRadioSnapsReportedBandwidthOntoTheOptionGrid() = runTest {
        // Firmware reports a rounded float, so an unedited apply would otherwise write 7.799 back.
        val sender = ScriptedCliSender().apply { respond = { "915.0,7.799,10,5" } }
        val controller = controller(sender)
        controller.fetchRadioSettings()
        assertEquals(7.8, controller.bandwidth)
    }

    @Test
    fun contactInfoModifiedGatedOnLoaded() = runTest {
        val controller = controller(ScriptedCliSender())
        controller.ownerInfo = "typed before load"
        // Not loaded yet: an edited value must not enable Apply and wipe the node's owner info.
        assertFalse(controller.contactInfoSettingsModified)
    }

    @Test
    fun passwordChangeBranches() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        assertIs<PasswordApplyResult.Empty>(controller.changePassword())

        controller.newPassword = "abc"
        controller.confirmPassword = "xyz"
        assertIs<PasswordApplyResult.Mismatch>(controller.changePassword())

        controller.confirmPassword = "abc"
        sender.respond = { "password now: abc" }
        assertIs<PasswordApplyResult.Applied>(controller.changePassword())
        assertEquals("", controller.newPassword)
    }

    @Test
    fun syncTimeClassifiesResponse() = runTest {
        val sender = ScriptedCliSender()
        val controller = controller(sender)
        sender.respond = { "ERR: clock cannot go backwards" }
        assertIs<SyncTimeResult.ClockAhead>(controller.syncTime())
        sender.respond = { "OK" }
        assertIs<SyncTimeResult.Synced>(controller.syncTime())
    }

    @Test
    fun syncTimeOnOkWithClockTextUpdatesDeviceTimeAndDrift() = runTest {
        val clock = "15:35 - 14/8/2026 UTC"
        val node = NodeSettingsResponseParser.utcEpochSeconds(clock)!!
        val sender = ScriptedCliSender().apply { respond = { "OK - clock set: $clock" } }
        val controller = NodeSettingsController(sender.send, sender.send, nowEpochSeconds = { node })

        assertIs<SyncTimeResult.Synced>(controller.syncTime())
        assertEquals(clock, controller.deviceTimeUtcString)
        assertEquals(0.0, controller.clockDriftSeconds)
    }

    @Test
    fun syncTimeOnBareOkClearsDriftWithoutWipingTime() = runTest {
        val clock = "06:40 - 18/4/2025 UTC"
        val node = NodeSettingsResponseParser.utcEpochSeconds(clock)!!
        val sender = ScriptedCliSender().apply { respond = { "OK - clock set" } }
        val controller = NodeSettingsController(sender.send, sender.send, nowEpochSeconds = { node + 10_000 })
        controller.applyDeviceTime(clock)
        assertEquals(-10_000.0, controller.clockDriftSeconds)

        assertIs<SyncTimeResult.Synced>(controller.syncTime())
        assertEquals(1, sender.sent.size)
        assertEquals(clock, controller.deviceTimeUtcString)
        assertEquals(null, controller.clockDriftSeconds)
    }

    @Test
    fun handleCommonLateResponseRecoversFieldAndClearsError() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.Timeout } }
        val controller = controller(sender)
        controller.fetchRadioSettings()
        assertTrue(controller.radioError)

        // A late radio CSV recovers the radio fields and clears the section error.
        val consumed = controller.handleCommonLateResponse("915.0,250.0,10,5")
        assertTrue(consumed)
        assertEquals(915.0, controller.frequency)
        assertFalse(controller.radioError)
    }

    @Test
    fun lateRadioReplyStoresEveryFieldAndSnapsRoundedBandwidth() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.Timeout } }
        val controller = controller(sender)
        controller.fetchRadioSettings()
        assertTrue(controller.radioError)

        // Text that is not a radio reading is not stored, and the section stays failed.
        assertFalse(controller.handleCommonLateResponse("not-radio"))
        assertEquals(null, controller.frequency)
        assertEquals(null, controller.bandwidth)
        assertTrue(controller.radioError)

        // Firmware reports bandwidth as a rounded float. The timely fetch snaps 7.799 to 7.8; a late
        // reply has to do the same or the picker shows no selection and an apply writes 7.799 back.
        assertTrue(controller.handleCommonLateResponse("915.0,7.799,10,5"))
        assertEquals(915.0, controller.frequency)
        assertEquals(7.8, controller.bandwidth)
        assertEquals(10, controller.spreadingFactor)
        assertEquals(5, controller.codingRate)
        assertFalse(controller.radioError)
        assertFalse(controller.radioSettingsModified)

        // A reading far from every listed option stays as reported.
        controller.fetchRadioSettings()
        assertTrue(controller.handleCommonLateResponse("910.0,137.0,13,4"))
        assertEquals(910.0, controller.frequency)
        assertEquals(137.0, controller.bandwidth)
        assertEquals(13, controller.spreadingFactor)
        assertEquals(4, controller.codingRate)
    }

    @Test
    fun lateLatitudeStoresTheCoordinateAndKeepsTheIdentityErrorUntilComplete() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd ->
                when (cmd) {
                    "get lon" -> "-122.4"
                    else -> throw RemoteNodeError.Timeout
                }
            }
        }
        val controller = controller(sender)
        controller.fetchIdentity()
        assertEquals(-122.4, controller.longitude)
        assertEquals(null, controller.latitude)
        assertTrue(controller.identityError)

        // A reply that is not a coordinate is not stored.
        assertFalse(controller.handleCommonLateResponse("not-a-coordinate"))
        assertEquals(null, controller.latitude)

        assertTrue(controller.handleCommonLateResponse("38.5"))
        assertEquals(38.5, controller.latitude)
        assertEquals(38.5, controller.originalLatitude)
        // The name never arrived, so the section stays failed after the coordinate lands.
        assertTrue(controller.identityError)

        controller.setNodeInfo(firmwareVersion = null, name = "Alpha", ownerInfo = null)
        controller.fetchIdentity()
        assertTrue(controller.handleCommonLateResponse("38.6"))
        assertEquals(38.6, controller.latitude)
        assertFalse(controller.identityError)
    }

    @Test
    fun lateReplyIsNotAttributedWhileBothCoordinatesAreUnanswered() = runTest {
        val sender = ScriptedCliSender().apply {
            respond = { cmd -> if (cmd == "get name") "Alpha Repeater" else throw RemoteNodeError.Timeout }
        }
        val controller = controller(sender)
        controller.fetchIdentity()
        assertTrue(controller.identityError)

        // "38.5" is a valid latitude and a valid longitude, so it belongs to neither; writing it into a
        // repeater's coordinates on a coin flip moves the node on the map and in every path it advertises.
        assertFalse(controller.handleCommonLateResponse("38.5"))
        assertEquals(null, controller.latitude)
        assertEquals(null, controller.longitude)
        assertTrue(controller.identityError)
    }

    @Test
    fun meshDuplicateOfAnAnsweredReplyIsNotRecoveredForAnotherQuery() = runTest {
        val answers = mapOf("get name" to "Alpha Repeater", "get lat" to "38.5")
        val sender = ScriptedCliSender().apply {
            respond = { cmd -> answers[cmd] ?: throw RemoteNodeError.Timeout }
        }
        val controller = controller(sender)
        controller.fetchIdentity()
        assertEquals(38.5, controller.latitude)

        // A flood-routed duplicate of the latitude reply must not become the longitude.
        assertFalse(controller.handleCommonLateResponse("38.5"))
        assertEquals(null, controller.longitude)

        assertTrue(controller.handleCommonLateResponse("-122.4"))
        assertEquals(-122.4, controller.longitude)
        assertFalse(controller.identityError)
    }

    @Test
    fun freeFormQueriesNeverClaimALateReply() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.Timeout } }
        val controller = controller(sender)
        controller.fetchDeviceInfo()
        controller.fetchContactInfo()
        assertTrue(controller.deviceInfoError)

        // `ver` and `get owner.info` parse any text, so an uncorrelated reply is not theirs to claim.
        assertFalse(controller.handleCommonLateResponse("KD7ABC / 145.230"))
        assertEquals(null, controller.firmwareVersion)
        assertEquals(null, controller.originalOwnerInfo)
    }

    @Test
    fun lateClockReplyIsRecoveredForTheQueryThatTimedOut() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.Timeout } }
        val controller = controller(sender)
        controller.fetchDeviceInfo()

        assertTrue(controller.handleCommonLateResponse("06:40 - 18/4/2025 UTC"))
        assertEquals("06:40 - 18/4/2025 UTC", controller.deviceTimeUtcString)
        // The firmware version never arrived, so the section keeps its error.
        assertTrue(controller.deviceInfoError)

        controller.setNodeInfo(firmwareVersion = "1.2.3", name = null, ownerInfo = null)
        controller.fetchDeviceInfo()
        assertTrue(controller.handleCommonLateResponse("06:41 - 18/4/2025 UTC"))
        assertEquals("06:41 - 18/4/2025 UTC", controller.deviceTimeUtcString)
        assertFalse(controller.deviceInfoError)
    }

    @Test
    fun rebootTreatsTheExpectedNoReplyTimeoutAsSent() = runTest {
        val sender = ScriptedCliSender().apply {
            // Firmware reboots without answering, so the short wait expiring is the success signal.
            respond = { throw RemoteNodeError.Timeout }
        }
        val controller = controller(sender)

        assertIs<ActionResult.Sent>(controller.reboot())
        assertEquals(RemoteOperationTimeoutPolicy.FIRE_AND_FORGET_CLI, sender.lastTimeout)
    }

    @Test
    fun rebootReportsARealTransportFailure() = runTest {
        val sender = ScriptedCliSender().apply { respond = { throw RemoteNodeError.NotConnected } }
        assertIs<ActionResult.TransportError>(controller(sender).reboot())
    }

    @Test
    fun sectionFetchesUseTheSharedDefaultCliWait() = runTest {
        val sender = ScriptedCliSender().apply { respond = { "1.0" } }
        controller(sender).fetchIdentity()

        assertEquals(RemoteOperationTimeoutPolicy.DEFAULT_CLI, sender.lastTimeout)
        assertTrue(sender.timeouts.all { it == RemoteOperationTimeoutPolicy.DEFAULT_CLI })
    }
}
