package one.meshcore.shared.settings

/** Debug log severity, mirroring the Swift `DebugLogLevel` (raw ints pinned; used in the exported log). */
enum class DebugLogLevel(val raw: Int) {
    DEBUG(0),
    INFO(1),
    NOTICE(2),
    WARNING(3),
    ERROR(4),
    FAULT(5),
    ;

    /** Uppercase label used in the exported log line (`[DEBUG]` … `[FAULT]`). */
    val label: String get() = name

    companion object {
        /** The level for a raw int, falling back to [INFO] for an unknown value (matching Swift). */
        fun fromRaw(raw: Int): DebugLogLevel = entries.firstOrNull { it.raw == raw } ?: INFO
    }
}
