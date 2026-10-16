package one.meshcore.shared.settings

/** Radio row facts for the Device Info section (iOS `generateDeviceSection`). */
data class DeviceDiagnostics(
    val isConnected: Boolean,
    val name: String,
    val firmwareVersionString: String,
    val firmwareVersion: Int,
    val manufacturerName: String,
    val buildDate: String,
    val frequencyKHz: Long,
    val bandwidthHz: Long,
    val spreadingFactor: Int,
    val codingRate: Int,
    val txPower: Int,
    val maxTxPower: Int,
    val maxContacts: Int,
    val maxChannels: Int,
    val manualAddContacts: Boolean,
    val multiAcks: Int,
)
