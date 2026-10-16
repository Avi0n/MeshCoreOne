package one.meshcore.shared.contact.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class ContactScanUnavailableIconTest {
    @Test
    fun unavailableUsesQrViewfinderAnalog() {
        assertEquals(AppIcons.QrCodeScanner, CONTACT_SCAN_UNAVAILABLE_ICON)
    }

    @Test
    fun permissionDeniedUsesQrViewfinderAnalog() {
        assertEquals(AppIcons.QrCodeScanner, CONTACT_SCAN_PERMISSION_DENIED_ICON)
    }
}
