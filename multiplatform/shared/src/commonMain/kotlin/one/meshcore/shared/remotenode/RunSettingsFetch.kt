package one.meshcore.shared.remotenode

/**
 * Runs one settings fetch. Returns true only for [RemoteNodeError.Timeout].
 * Other [RemoteNodeError] values return false. Every other throwable propagates.
 */
internal suspend inline fun runSettingsFetch(block: () -> Unit): Boolean = try {
    block()
    false
} catch (_: RemoteNodeError.Timeout) {
    true
} catch (_: RemoteNodeError) {
    false
}
