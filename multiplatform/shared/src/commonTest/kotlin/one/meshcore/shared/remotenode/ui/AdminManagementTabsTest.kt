package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_cli
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_settings
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_tab_telemetry
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminManagementTabsTest {
    @Test
    fun tabsStaySettingsThenCliThenTelemetry() {
        assertEquals(
            listOf(
                AdminManagementTab.SETTINGS to Res.string.remotenodes_remote_nodes_settings_tab_settings,
                AdminManagementTab.CLI to Res.string.remotenodes_remote_nodes_settings_tab_cli,
                AdminManagementTab.TELEMETRY to Res.string.remotenodes_remote_nodes_settings_tab_telemetry,
            ),
            AdminManagementTab.entries.map { it to it.labelResource() },
        )
    }
}
