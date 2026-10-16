package one.meshcore.shared.settings

/**
 * Flushes [buffer], then age-then-ceiling prunes and returns what [DebugLogRetention] still keeps,
 * formatted by [format]. Prune and fetch share [nowMillis] so a row cannot fall on different sides of
 * the window. The connect-time prune stays in `ServiceContainer.start`.
 */
suspend fun exportRetainedDebugLog(
    buffer: DebugLogBuffer,
    store: DebugLogStore,
    nowMillis: Long,
    format: suspend (List<DebugLogEntryDto>) -> String,
): String {
    buffer.flush()
    val cutoff = nowMillis - DebugLogRetention.WINDOW_MS
    store.pruneDebugLogEntries(olderThanMillis = cutoff, keepCount = DebugLogRetention.MAX_ENTRIES)
    return format(store.fetchDebugLogEntries(since = cutoff, limit = DebugLogRetention.MAX_ENTRIES))
}
