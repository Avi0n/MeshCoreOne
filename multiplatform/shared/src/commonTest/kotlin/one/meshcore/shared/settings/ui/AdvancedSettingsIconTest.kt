package one.meshcore.shared.settings.ui

import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class AdvancedSettingsIconTest {
    @Test
    fun configExportUsesShareArrowUpAnalog() {
        assertEquals(AppIcons.Share, ADVANCED_CONFIG_EXPORT_ICON)
    }

    @Test
    fun configImportUsesDownloadArrowDownAnalog() {
        assertEquals(AppIcons.CloudDownload, ADVANCED_CONFIG_IMPORT_ICON)
    }

    @Test
    fun floodScopeDiscoverUsesAntenna() {
        assertEquals(AppIcons.CellTower, FLOOD_SCOPE_DISCOVER_ICON)
    }

    @Test
    fun floodScopeManageUsesListBulletAnalog() {
        assertEquals(AppIcons.ListAlt, FLOOD_SCOPE_MANAGE_ICON)
    }
}
