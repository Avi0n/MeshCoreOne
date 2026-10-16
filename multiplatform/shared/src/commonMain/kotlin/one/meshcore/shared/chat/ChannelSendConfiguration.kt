package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.DeviceDto

/** Saved rows used to resolve one channel send attempt. Missing rows retain Swift's fallback behavior. */
data class ChannelSendConfiguration(val channel: ChannelDto?, val device: DeviceDto?)
