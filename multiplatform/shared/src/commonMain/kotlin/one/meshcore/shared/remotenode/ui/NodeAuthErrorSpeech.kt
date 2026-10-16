package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_error_prefix
import one.meshcore.shared.ui.formatTemplate

/** iOS `Auth.errorPrefix` — TalkBack speaks Error: then the visible failure, matching VoiceOver. */
internal fun authErrorSpokenResource() = Res.string.remotenodes_remote_nodes_auth_error_prefix

internal fun authErrorSpokenLabel(errorText: String, prefixTemplate: String): String =
    formatTemplate(prefixTemplate, listOf(errorText))
