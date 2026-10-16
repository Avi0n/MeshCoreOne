package one.meshcore.shared.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import one.meshcore.shared.battery.BatteryDisplay
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_device_info_battery
import one.meshcore.shared.resources.settings_device_info_battery_and_storage
import one.meshcore.shared.resources.settings_device_info_power_storage_header
import one.meshcore.shared.resources.settings_device_info_storage_used
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

private val StorageBarShape = RoundedCornerShape(4.dp)
private const val STORAGE_WARNING_RATIO = 0.7f
private const val STORAGE_CRITICAL_RATIO = 0.9f
private val StorageBarHeight = 8.dp
private val StorageLoadingIndicatorSize = 24.dp

/**
 * Settings → Device Info battery and storage block, matching iOS `DeviceInfoView`'s Power & Storage
 * section: formatted binary-KB counts, a usage bar, and a loading row before the first battery read.
 */
@Composable
internal fun DevicePowerStorageSection(batteryInfo: BatteryInfo?, ocvArray: List<Int>, modifier: Modifier = Modifier) {
    PreferenceHubSection(
        modifier = modifier,
        title = stringResource(Res.string.settings_device_info_power_storage_header),
    ) {
        DevicePowerStorageBody(batteryInfo = batteryInfo, ocvArray = ocvArray)
    }
}

@Composable
private fun DevicePowerStorageBody(batteryInfo: BatteryInfo?, ocvArray: List<Int>) {
    if (batteryInfo == null) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.settings_device_info_battery_and_storage)) },
            leadingContent = {
                Icon(
                    imageVector = AppIcons.BatteryFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                CircularProgressIndicator(modifier = Modifier.size(StorageLoadingIndicatorSize))
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    PreferenceHubValueRow(
        stringResource(Res.string.settings_device_info_battery),
        "${BatteryDisplay.percentText(batteryInfo, ocvArray)} (${BatteryDisplay.voltageText(batteryInfo)})",
        icon = AppIcons.BatteryFull,
    )
    val used = batteryInfo.usedStorageKB ?: 0L
    val total = batteryInfo.totalStorageKB ?: 0L
    PreferenceHubValueRow(
        stringResource(Res.string.settings_device_info_storage_used),
        formatStorage(used, total),
        icon = AppIcons.Storage,
    )
    StorageBar(
        used = used,
        total = total,
        modifier = Modifier.padding(horizontal = PreferenceHubCardPadding),
    )
}

@Composable
internal fun StorageBar(used: Long, total: Long, modifier: Modifier = Modifier) {
    val ratio = if (total > 0) (used.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
    val fill = when {
        ratio < STORAGE_WARNING_RATIO -> MaterialTheme.colorScheme.primary
        ratio < STORAGE_CRITICAL_RATIO -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Box(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp)
            .height(StorageBarHeight)
            .clip(StorageBarShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(ratio)
                .background(fill),
        )
    }
}
