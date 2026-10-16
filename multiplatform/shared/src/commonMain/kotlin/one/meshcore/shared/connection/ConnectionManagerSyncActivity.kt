package one.meshcore.shared.connection

internal fun ConnectionManager.startSyncActivity(container: ServiceContainer) {
    if (services === container) connectionUi?.syncStarted()
}

internal fun ConnectionManager.endSyncActivity(container: ServiceContainer, succeeded: Boolean) {
    if (services === container) connectionUi?.syncEnded(succeeded)
}
