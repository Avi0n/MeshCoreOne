package one.meshcore.shared.settings

/** Device limits and current rows the import planner checks a file against. */
data class ConfigImportDevice(
    val maxChannels: Int,
    val maxContacts: Int,
    val maxTxPower: Byte,
    val existingChannels: List<DeviceChannelSlot>,
    val existingContacts: Map<String, ExistingContact>,
)
