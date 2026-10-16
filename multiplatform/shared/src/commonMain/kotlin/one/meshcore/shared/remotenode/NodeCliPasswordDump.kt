package one.meshcore.shared.remotenode

import one.meshcore.shared.model.Uuid

/** iOS `RemoteNodeService+CLI.handlePasswordChangeIfNeeded` success dump. */
fun clearedStoredPasswordAfterPasswordChangeLine(sessionID: Uuid): String =
    "Cleared stored password after password change for session ${sessionID.value}"

/** iOS `RemoteNodeService+CLI.handlePasswordChangeIfNeeded` persist-fail dump. */
fun failedToClearStoredPasswordLine(sessionID: Uuid, detail: String): String =
    "Failed to clear stored password for session ${sessionID.value}: $detail"
