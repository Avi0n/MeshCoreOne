package one.meshcore.shared.channel

internal enum class ChannelSyncOperationCase {
    PIPELINED,
    SERIAL,
    RETRY,
}
