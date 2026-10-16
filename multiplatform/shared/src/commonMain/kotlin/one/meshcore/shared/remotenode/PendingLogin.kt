package one.meshcore.shared.remotenode

import kotlinx.coroutines.CompletableDeferred
import one.meshcore.shared.model.Uuid

internal data class PendingLogin(val sessionID: Uuid, val deferred: CompletableDeferred<LoginResult>)
