package one.meshcore.shared.channel.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ChannelScanUnavailableIconTest {
    @Test
    fun permissionDeniedUsesQrViewfinderAnalog() {
        assertEquals(AppIcons.QrCodeScanner, CHANNEL_SCAN_PERMISSION_DENIED_ICON)
    }
}
