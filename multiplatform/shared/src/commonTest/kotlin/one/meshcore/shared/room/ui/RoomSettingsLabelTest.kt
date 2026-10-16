package one.meshcore.shared.room.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_name
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_device_info_footer
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_radio_footer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomSettingsLabelTest {
    @Test
    fun identityNameUsesRemoteNodesName() {
        assertEquals(Res.string.remotenodes_remote_nodes_name, roomIdentityNameLabel())
    }

    @Test
    fun radioSectionShowsRadioFooter() {
        assertEquals(Res.string.remotenodes_remote_nodes_settings_radio_footer, roomRadioFooter())
    }

    @Test
    fun deviceInfoSectionShowsFirmwareClockFooter() {
        assertEquals(Res.string.remotenodes_remote_nodes_settings_device_info_footer, roomDeviceInfoFooter())
    }

    @Test
    fun anUnloadedRoomAccessFieldStaysDisabled() {
        assertFalse(roomAccessFieldEnabled(valueLoaded = false, isApplying = false))
        assertFalse(roomAccessFieldEnabled(valueLoaded = true, isApplying = true))
        assertTrue(roomAccessFieldEnabled(valueLoaded = true, isApplying = false))
    }

    @Test
    fun anUnloadedRoomAccessFieldShowsLoadingOrFailed() {
        val copy = RoomAccessLoadCopy(loading = "Loading…", failed = "Failed to load")
        assertEquals(
            "Loading…",
            roomAccessFieldLoadCaption(
                valueLoaded = false,
                isLoading = true,
                hasError = false,
                copy = copy,
            ),
        )
        assertEquals(
            "Failed to load",
            roomAccessFieldLoadCaption(
                valueLoaded = false,
                isLoading = false,
                hasError = true,
                copy = copy,
            ),
        )
        assertEquals(
            "—",
            roomAccessFieldLoadCaption(
                valueLoaded = false,
                isLoading = false,
                hasError = false,
                copy = copy,
            ),
        )
        assertEquals(
            null,
            roomAccessFieldLoadCaption(
                valueLoaded = true,
                isLoading = false,
                hasError = true,
                copy = copy,
            ),
        )
    }
}
