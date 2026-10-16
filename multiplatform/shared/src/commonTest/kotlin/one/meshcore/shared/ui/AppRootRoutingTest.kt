package one.meshcore.shared.ui

import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.SettingsDestination
import one.meshcore.shared.navigation.ToolSelection
import one.meshcore.shared.remotenode.NodeAuthPurpose
import one.meshcore.shared.settings.LocationSettingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppRootRoutingTest {
    @Test
    fun backIsHandledForASheetAToolASettingOrAStack() {
        assertFalse(shellCanHandleBack(null, AppTab.CHATS, null, null, null))
        assertFalse(shellCanHandleBack(null, AppTab.CHATS, null, null, emptyList<String>()))
        assertTrue(shellCanHandleBack(ShellSheet.AddContact, AppTab.CHATS, null, null, null))
        assertFalse(shellCanHandleBack(null, AppTab.TOOLS, null, null, null))
        assertTrue(shellCanHandleBack(null, AppTab.TOOLS, ToolSelection.CLI, null, null))
        assertFalse(shellCanHandleBack(null, AppTab.SETTINGS, null, null, null))
        assertTrue(shellCanHandleBack(null, AppTab.SETTINGS, null, SettingsDestination.ADVANCED, null))
        assertTrue(shellCanHandleBack(null, AppTab.NODES, null, null, listOf("row")))
    }

    @Test
    fun aZeroCoordinateRowIsNotANodeFix() {
        assertNull(shellNodeFix(null))
        assertNull(shellNodeFix(LocationSettingsSnapshot(0.0, 0.0, 0)))
        assertEquals(LatLng(1.5, -2.5), shellNodeFix(LocationSettingsSnapshot(1.5, -2.5, 0)))
    }

    @Test
    fun chatTelemetrySkipsNodeAuth() {
        val chat = ChatFixtures.contact("Ada")
        assertTrue(contactTelemetrySheet(chat) is ShellSheet.NodeTelemetry)
        assertNull(contactNodeAuthSheet(chat, NodeAuthPurpose.JOIN))
    }

    @Test
    fun repeaterTelemetryAndManagementAuthenticate() {
        val repeater = ChatFixtures.contact("Ridge").copy(typeRawValue = ContactType.REPEATER.raw)
        val telemetry = contactTelemetrySheet(repeater) as ShellSheet.NodeAuth
        val management = contactNodeAuthSheet(repeater, NodeAuthPurpose.MANAGE) as ShellSheet.NodeAuth
        assertEquals(NodeAuthPurpose.TELEMETRY, telemetry.purpose)
        assertEquals(NodeAuthPurpose.MANAGE, management.purpose)
    }
}
