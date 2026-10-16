package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_device_info_no_device_description
import one.meshcore.shared.resources.settings_device_info_no_device_title
import one.meshcore.shared.ui.IconLeadEmptyColumn
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsNoDeviceEmpty(modifier: Modifier = Modifier) {
    IconLeadEmptyColumn(
        title = stringResource(Res.string.settings_device_info_no_device_title),
        description = stringResource(Res.string.settings_device_info_no_device_description),
        icon = AppIcons.CellTowerOff,
        modifier = modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}
