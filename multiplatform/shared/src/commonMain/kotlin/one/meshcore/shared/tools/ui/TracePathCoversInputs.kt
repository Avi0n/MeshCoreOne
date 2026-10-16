package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CoroutineScope
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.ui.AppHaptics

/** Immutable values rendered by the trace-path sheets and dialogs. */
internal data class TracePathCoversState(val resultMatchesPath: Boolean, val hashSize: Int, val nowSeconds: Long)

/** Services and actions used by the trace-path sheets and dialogs. */
internal data class TracePathCoversDependencies(
    val builder: TracePathBuilder,
    val savedPathStore: SavedTracePathStore?,
    val radioId: () -> Uuid?,
    val scope: CoroutineScope,
    val haptics: AppHaptics,
    val reloadSaved: suspend () -> Unit,
    val resetPath: () -> Unit,
)

/** Localized failure text supplied by the trace-path composition root. */
internal data class TracePathCoversMessages(
    val saveFailed: String,
    val loadFailed: String,
    val renameFailed: String,
    val deleteFailed: String,
)
