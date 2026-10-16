package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * A scope for a destructive commit that must finish even when its screen goes away. Compose cancels the
 * scope [rememberCoroutineScope] returns as the call site leaves composition, so a multi-step write
 * launched there is aborted part-way by ordinary back navigation, leaving whatever it was writing half
 * applied. This scope carries the composition's dispatcher but not its job: dispatch behaviour is
 * unchanged, and only the cancellation parent differs.
 *
 * Nothing cancels the returned scope — it is unparented by design — so every launch on it must be bounded
 * and must carry its own cancellation path, such as an explicit Cancel control that cancels the returned
 * [Job]. Work that should stop when its screen does (a trace, a scan, a query, a page load) belongs on
 * [rememberCoroutineScope] instead.
 */
@Composable
internal fun rememberDetachedScope(): CoroutineScope {
    val composition = rememberCoroutineScope()
    return remember(composition) {
        CoroutineScope(SupervisorJob() + composition.coroutineContext.minusKey(Job))
    }
}
