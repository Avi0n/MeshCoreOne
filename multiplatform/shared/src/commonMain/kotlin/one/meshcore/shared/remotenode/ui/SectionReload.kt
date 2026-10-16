package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** A cancelled load can publish accepted partial fields while its model remains live. */
internal fun <S> MutableStateFlow<S>.replaceSectionLoad(
    request: RemoteSectionRequest,
    begin: (S) -> S,
    cancelled: (S) -> S,
    load: suspend () -> Unit,
) {
    request.launch(
        begin = { update(begin) },
        onCancel = { update(cancelled) },
        onFailure = { throw it },
        read = load,
    )
}
