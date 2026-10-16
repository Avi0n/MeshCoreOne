package one.meshcore.shared.settings

/** Live battery readout for the Battery section (iOS `generateBatterySection`). */
data class BatteryDiagnostics(val percentage: Int, val voltage: Double, val millivolts: Int)
