package one.meshcore.shared.settings.ui

import one.meshcore.shared.battery.OCVPreset

internal data class BatteryParentSave(val preset: OCVPreset, val values: List<Int>)
