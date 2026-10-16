package one.meshcore.shared.remotenode

import one.meshcore.shared.model.Uuid
import kotlin.time.Duration

/** iOS `RemoteNodeService.handleLoginResult` lookup dump. */
fun handleLoginResultLookingLine(prefixHex: String, pendingKeys: List<String>): String =
    "handleLoginResult: looking for prefix $prefixHex, pending keys: $pendingKeys"

/** iOS `RemoteNodeService.handleLoginResult` continuation dump. */
fun handleLoginResultFoundLine(prefixHex: String): String =
    "handleLoginResult: found continuation for prefix $prefixHex"

/** iOS `RemoteNodeService.handleLoginResult` persist dump. */
fun handleLoginResultUpdatingLine(sessionID: Uuid, permissionRaw: Int): String =
    "handleLoginResult: updating session ${sessionID.value} isConnected=true, permission=$permissionRaw"

/** iOS `RemoteNodeService.handleLoginResult` verify-success dump. */
fun handleLoginResultVerifiedLine(sessionID: Uuid): String =
    "handleLoginResult: verified session ${sessionID.value} isConnected=true"

/** iOS `RemoteNodeService.login` pending-register dump. */
fun loginRegisteringPendingLine(prefixHex: String): String = "login: registering pending login for prefix $prefixHex"

/** iOS `RemoteNodeService.login` send-ok dump. */
fun loginSendSucceededLine(timeout: Duration, prefixHex: String): String =
    "login: send succeeded, starting $timeout timeout for prefix $prefixHex"

/** iOS `RemoteNodeService.login` retransmit-stop dump. */
fun loginContinuationConsumedLine(prefixHex: String): String =
    "login: continuation consumed for prefix $prefixHex; stopping retransmits"

/** iOS `RemoteNodeService.login` retransmit dump. */
fun loginRetransmitLine(count: Int, prefixHex: String): String = "login: retransmit #$count for prefix $prefixHex"

/** iOS `RemoteNodeService.login` late-timeout dump. */
fun loginTimeoutAlreadyConsumedLine(prefixHex: String): String =
    "login: timeout elapsed but continuation already consumed for prefix $prefixHex"
