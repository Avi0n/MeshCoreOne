package one.meshcore.shared.contact

/** Telemetry-permission flags (bits 1–3): base, location, and environment. */
internal const val TELEMETRY_FLAGS: Int = 0x0E

/** Whether [flags] has any telemetry-permission bit set. */
fun hasTelemetryPermissions(flags: Int): Boolean = (flags and TELEMETRY_FLAGS) != 0
