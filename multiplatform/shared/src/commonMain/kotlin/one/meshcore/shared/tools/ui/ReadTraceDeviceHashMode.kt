package one.meshcore.shared.tools.ui

import one.meshcore.shared.runExceptionFallback

/** Missing capabilities use the trace builder's default hash mode; cancellation stops screen loading. */
internal suspend fun readTraceDeviceHashMode(read: suspend () -> Int?): Int? = runExceptionFallback({ null }, read)
