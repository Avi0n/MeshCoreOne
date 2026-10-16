package one.meshcore.shared.remotenode

/** Saved visits win. The demo fills an empty history only when the screen asked for it. */
internal fun <T> statusHistorySnapshots(saved: List<T>, useDemo: Boolean, demo: () -> List<T>): List<T> = when {
    saved.isNotEmpty() -> saved
    useDemo -> demo()
    else -> emptyList()
}
