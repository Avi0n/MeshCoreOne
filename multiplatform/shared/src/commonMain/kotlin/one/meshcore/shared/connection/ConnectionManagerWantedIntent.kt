package one.meshcore.shared.connection

/** Live wanted intent wins; otherwise an eligible saved device authorizes automatic restoration. */
suspend fun ConnectionManager.isConnectionWanted(): Boolean {
    while (!wantsConnection) {
        val generation = connectGeneration
        val intent = intentStore?.restore()
        if (ownsConnectAttempt(generation) && !wantsConnection) {
            val savedDeviceId = lastConnectionStore?.deviceId()
            if (ownsConnectAttempt(generation)) {
                return wantsConnection || (intent?.isUserDisconnected != true && savedDeviceId != null)
            }
        }
    }
    return true
}
