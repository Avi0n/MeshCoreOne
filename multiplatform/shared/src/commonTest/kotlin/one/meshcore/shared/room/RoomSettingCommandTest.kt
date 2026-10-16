package one.meshcore.shared.room

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The room admin get/set command strings, response parsing, and changed-field diffs (W5 spec §9). These
 * are pure and have no Swift unit-test analog: the Swift `RoomSettingsViewModel` exercises them only
 * through the live CLI.
 */
class RoomSettingCommandTest {
    @Test
    fun buildsGetAndSetStrings() {
        assertEquals("get guest.password", RoomSettingCommand.GET_GUEST_PASSWORD)
        assertEquals("set guest.password hunter2", RoomSettingCommand.setGuestPassword("hunter2"))
        assertEquals("set allow.read.only on", RoomSettingCommand.setAllowReadOnly(true))
        assertEquals("set allow.read.only off", RoomSettingCommand.setAllowReadOnly(false))
        assertEquals("set advert.interval 30", RoomSettingCommand.setAdvertInterval(30))
        assertEquals("set flood.advert.interval 6", RoomSettingCommand.setFloodAdvertInterval(6))
        assertEquals("set flood.max 3", RoomSettingCommand.setFloodMax(3))
    }

    @Test
    fun parsesGuestPasswordStatusWordsAsEmpty() {
        assertEquals("", RoomSettingCommand.parseGuestPassword("ok"))
        assertEquals("", RoomSettingCommand.parseGuestPassword("ERROR"))
        assertEquals("", RoomSettingCommand.parseGuestPassword("  unknown  "))
    }

    @Test
    fun parsesGuestPasswordValueStrippingEcho() {
        assertEquals("s3cret", RoomSettingCommand.parseGuestPassword("> s3cret"))
        assertEquals("plain", RoomSettingCommand.parseGuestPassword("plain"))
    }

    @Test
    fun parsesAllowReadOnly() {
        assertTrue(RoomSettingCommand.parseAllowReadOnly("on"))
        assertTrue(RoomSettingCommand.parseAllowReadOnly("> ON"))
        assertFalse(RoomSettingCommand.parseAllowReadOnly("off"))
    }

    @Test
    fun recognizesSetAcknowledgement() {
        assertTrue(RoomSettingCommand.isSetOk("ok"))
        assertTrue(RoomSettingCommand.isSetOk("  OK "))
        assertFalse(RoomSettingCommand.isSetOk("error"))
    }

    @Test
    fun roomAccessDiffEmitsOnlyChangedFields() {
        val original = RoomAccessSettings(guestPassword = "old", allowReadOnly = false)
        val desired = RoomAccessSettings(guestPassword = "new", allowReadOnly = false)

        assertEquals(listOf("set guest.password new"), RoomSettingCommand.changedRoomAccessCommands(original, desired))
    }

    @Test
    fun roomAccessDiffSkipsNullAndUnchanged() {
        val original = RoomAccessSettings(guestPassword = "same", allowReadOnly = true)
        val desired = RoomAccessSettings(guestPassword = "same", allowReadOnly = null)

        assertTrue(RoomSettingCommand.changedRoomAccessCommands(original, desired).isEmpty())
    }

    @Test
    fun behaviorDiffEmitsChangedFieldsInOrder() {
        val original = RoomBehaviorSettings(advertIntervalMinutes = 15, floodAdvertIntervalHours = 6, floodMaxHops = 3)
        val desired = RoomBehaviorSettings(advertIntervalMinutes = 30, floodAdvertIntervalHours = 6, floodMaxHops = 5)

        assertEquals(
            listOf("set advert.interval 30", "set flood.max 5"),
            RoomSettingCommand.changedBehaviorCommands(original, desired),
        )
    }
}
