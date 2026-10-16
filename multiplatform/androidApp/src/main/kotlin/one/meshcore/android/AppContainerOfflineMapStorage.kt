package one.meshcore.android

fun AppContainer.resumeOfflineMapPack(packId: String) {
    val pack = offlineMapPackRegistry.get(packId) ?: return
    offlineMapPackRegistry.upsert(pack.copy(paused = false))
    persistOfflineMapPacks()
    offlineMapTileDownloader.resumeDownload(
        pack = pack.copy(paused = false),
        onUpdate = { updated ->
            offlineMapPackRegistry.upsert(updated)
            persistOfflineMapPacks()
        },
        onError = { reportOfflineMapDownloadError(it) },
    )
}

/** Free bytes on the app files volume for the offline region free-space gate. */
fun AppContainer.offlineMapAvailableBytes(): Long? = runCatching {
    val stat = android.os.StatFs(appContext.filesDir.absolutePath)
    stat.availableBlocksLong * stat.blockSizeLong
}.getOrNull()

/** MapLibre offline db file length for Settings → Offline Maps Storage Used. */
fun AppContainer.offlineMapDatabaseSizeBytes(): Long {
    val name = "mbgl-offline.db"
    val candidates = listOf(
        java.io.File(appContext.filesDir, name),
        appContext.getDatabasePath(name),
    )
    return candidates.firstOrNull { it.exists() }?.length() ?: 0L
}
