package one.meshcore.shared.tools

fun filterRxLogEntries(
    entries: List<RxLogEntry>,
    routeFilter: RxLogRouteFilter,
    decryptFilter: RxLogDecryptFilter,
): List<RxLogEntry> = entries.filter { entry ->
    when (routeFilter) {
        RxLogRouteFilter.ALL -> Unit
        RxLogRouteFilter.FLOOD_ONLY -> if (!entry.isFlood) return@filter false
        RxLogRouteFilter.DIRECT_ONLY -> if (entry.isFlood) return@filter false
    }
    when (decryptFilter) {
        RxLogDecryptFilter.ALL -> Unit
        RxLogDecryptFilter.DECRYPTED -> if (entry.decryptStatus != DecryptStatus.SUCCESS) return@filter false
        RxLogDecryptFilter.FAILED -> if (!entry.decryptStatus.isFailed) return@filter false
    }
    true
}
