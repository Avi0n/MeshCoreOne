package one.meshcore.shared.remotenode

import one.meshcore.shared.model.Uuid

/** iOS `RemoteNodeService.handleEvent` login-success dump. */
fun loginSuccessReceivedLine(prefixHex: String): String = "loginSuccess received for prefix $prefixHex"

/** iOS `RemoteNodeService.logout` send-fail dump. */
fun logoutSendFailedLine(detail: String): String = "Logout send failed (ignoring): $detail"

/** iOS `RemoteNodeService.addLocalContactToRadio` success dump. */
const val RE_ADDED_MISSING_CONTACT_DURING_LOGIN = "Re-added missing contact to radio during login"

/** iOS `RemoteNodeService.createSession` reuse dump. */
fun createSessionReusingLine(sessionID: Uuid, pubKeyHex: String, isConnected: Boolean): String =
    "createSession: reusing existing session ${sessionID.value} for $pubKeyHex, isConnected=$isConnected"

/** iOS `RemoteNodeService.createSession` new-row dump. */
fun createSessionCreatingLine(pubKeyHex: String): String = "createSession: creating new session for $pubKeyHex"

/** iOS `RemoteNodeService.createSession` saved dump. */
fun createSessionSavedLine(sessionID: Uuid, pubKeyHex: String): String =
    "createSession: saved session ${sessionID.value} for $pubKeyHex"
