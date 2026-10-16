package one.meshcore.shared.settings

import one.meshcore.shared.contact.AutoAddConfigBits
import one.meshcore.shared.contact.AutoAddMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodesAutoAddTest {
    @Test
    fun firmwareVersionGatesAutoAddConfigAtV112() {
        assertFalse(FirmwareVersion.isAtLeast("", major = 1, minor = 12))
        assertFalse(FirmwareVersion.isAtLeast("v1.11.9", major = 1, minor = 12))
        assertTrue(FirmwareVersion.isAtLeast("v1.12.0", major = 1, minor = 12))
        assertTrue(FirmwareVersion.isAtLeast("1.14.0-rc1", major = 1, minor = 12))
        assertTrue(FirmwareVersion.isAtLeast("v2.0", major = 1, minor = 12))
    }

    @Test
    fun snapshotSupportsFlagsFromFirmwareString() {
        val old = NodesAutoAddSnapshot(
            manualAddContacts = true,
            autoAddConfig = 0,
            autoAddMaxHops = 0,
            firmwareVersionString = "v1.11.0",
        )
        assertFalse(old.supportsAutoAddConfig)
        assertFalse(old.supportsAutoAddMaxHops)

        val mid = old.copy(firmwareVersionString = "v1.12.0")
        assertTrue(mid.supportsAutoAddConfig)
        assertFalse(mid.supportsAutoAddMaxHops)

        val new = old.copy(firmwareVersionString = "v1.14.0")
        assertTrue(new.supportsAutoAddConfig)
        assertTrue(new.supportsAutoAddMaxHops)
    }

    @Test
    fun draftBitmaskMatchesIosSelectedTypesAndOverwrite() {
        val draft = NodesAutoAddDraft(
            mode = AutoAddMode.SELECTED_TYPES,
            autoAddContacts = true,
            autoAddRepeaters = true,
            autoAddRoomServers = false,
            overwriteOldest = true,
            autoAddMaxHops = 2,
        )
        val expected = AutoAddConfigBits.OVERWRITE_OLDEST or
            AutoAddConfigBits.CONTACTS or
            AutoAddConfigBits.REPEATERS
        assertEquals(expected, draft.toAutoAddConfigBitmask())
        assertTrue(draft.manualAddContactsForWire())
    }

    @Test
    fun allModeClearsTypeBitsAndDisablesManualAdd() {
        val draft = NodesAutoAddDraft(
            mode = AutoAddMode.ALL,
            autoAddContacts = true,
            overwriteOldest = true,
        )
        assertEquals(AutoAddConfigBits.OVERWRITE_OLDEST, draft.toAutoAddConfigBitmask())
        assertFalse(draft.manualAddContactsForWire())
    }

    @Test
    fun manualModeClearsTypeBitsKeepsOverwrite() {
        val draft = NodesAutoAddDraft(
            mode = AutoAddMode.MANUAL,
            autoAddContacts = true,
            overwriteOldest = true,
        )
        assertEquals(AutoAddConfigBits.OVERWRITE_OLDEST, draft.toAutoAddConfigBitmask())
        assertTrue(draft.manualAddContactsForWire())
    }

    @Test
    fun toDraftFallsBackToManualOrAllOnOldFirmware() {
        val manual = NodesAutoAddSnapshot(
            manualAddContacts = true,
            autoAddConfig = AutoAddConfigBits.CONTACTS,
            autoAddMaxHops = 3,
            firmwareVersionString = "v1.10.0",
        ).toDraft()
        assertEquals(AutoAddMode.MANUAL, manual.mode)
        assertFalse(manual.autoAddContacts)

        val all = NodesAutoAddSnapshot(
            manualAddContacts = false,
            autoAddConfig = 0,
            autoAddMaxHops = 0,
            firmwareVersionString = "v1.10.0",
        ).toDraft()
        assertEquals(AutoAddMode.ALL, all.mode)
    }

    @Test
    fun settingsModifiedComparesDraftAgainstSnapshot() {
        val snapshot = NodesAutoAddSnapshot(
            manualAddContacts = true,
            autoAddConfig = AutoAddConfigBits.CONTACTS,
            autoAddMaxHops = 3,
            firmwareVersionString = "v1.14.0",
        )
        assertFalse(snapshot.settingsModified(snapshot.toDraft()))
        assertTrue(snapshot.settingsModified(snapshot.toDraft().copy(autoAddMaxHops = 2)))
        assertTrue(snapshot.settingsModified(snapshot.toDraft().copy(mode = AutoAddMode.ALL)))
    }
}
