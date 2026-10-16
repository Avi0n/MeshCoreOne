package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertEquals

/** Mirrors the iOS `AutoAddMode.mode` mapping: manual-add + type bits gate Discover population. */
class AutoAddModeTest {
    @Test
    fun manualAddOffIsAll() {
        assertEquals(AutoAddMode.ALL, AutoAddMode.mode(manualAddContacts = false, autoAddConfig = 0))
        // Even with type bits, manual-add off means the firmware auto-adds everything.
        assertEquals(
            AutoAddMode.ALL,
            AutoAddMode.mode(manualAddContacts = false, autoAddConfig = AutoAddConfigBits.REPEATERS),
        )
    }

    @Test
    fun manualAddOnWithNoTypeBitsIsManual() {
        assertEquals(AutoAddMode.MANUAL, AutoAddMode.mode(manualAddContacts = true, autoAddConfig = 0))
        // The overwrite-oldest bit is not a type bit, so the mode stays manual.
        assertEquals(
            AutoAddMode.MANUAL,
            AutoAddMode.mode(manualAddContacts = true, autoAddConfig = AutoAddConfigBits.OVERWRITE_OLDEST),
        )
    }

    @Test
    fun manualAddOnWithAnyTypeBitIsSelectedTypes() {
        assertEquals(
            AutoAddMode.SELECTED_TYPES,
            AutoAddMode.mode(manualAddContacts = true, autoAddConfig = AutoAddConfigBits.CONTACTS),
        )
        assertEquals(
            AutoAddMode.SELECTED_TYPES,
            AutoAddMode.mode(manualAddContacts = true, autoAddConfig = AutoAddConfigBits.SENSORS),
        )
    }

    @Test
    fun typeBitsMaskCoversEveryTypeBit() {
        val expected = AutoAddConfigBits.CONTACTS or AutoAddConfigBits.REPEATERS or
            AutoAddConfigBits.ROOM_SERVERS or AutoAddConfigBits.SENSORS
        assertEquals(expected, AutoAddConfigBits.TYPE_BITS_MASK)
        assertEquals(0x1E, AutoAddConfigBits.TYPE_BITS_MASK)
    }
}
