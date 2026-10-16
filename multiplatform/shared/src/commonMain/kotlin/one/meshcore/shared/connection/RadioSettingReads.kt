package one.meshcore.shared.connection

import one.meshcore.shared.meshcore.AutoAddConfig
import one.meshcore.shared.meshcore.SelfInfo

/**
 * Reads companion self-info. A thrown failure is logged and comes back null so the caller can
 * keep the Device row. Cancellation still stops the caller, and an error still propagates.
 */
internal suspend fun readReportedSelfInfo(warning: (String) -> Unit, read: suspend () -> SelfInfo): SelfInfo? =
    when (val result = readAnsweredRadioCommand(warning, SELF_INFO_READ_FAILED, read)) {
        AnsweredRadioRead.Failed -> null
        is AnsweredRadioRead.Value -> result.value
    }

/**
 * Reads the live auto-add policy. A thrown failure is logged and comes back [stored].
 * Cancellation still stops the caller, and an error still propagates.
 */
internal suspend fun readAutoAddConfigOrStored(
    warning: (String) -> Unit,
    stored: AutoAddConfig,
    read: suspend () -> AutoAddConfig,
): AutoAddConfig = when (val result = readAnsweredRadioCommand(warning, AUTO_ADD_CONFIG_READ_FAILED, read)) {
    AnsweredRadioRead.Failed -> stored
    is AnsweredRadioRead.Value -> result.value
}

internal const val SELF_INFO_READ_FAILED = "Self info read failed"

internal const val AUTO_ADD_CONFIG_READ_FAILED = "Auto-add config read failed"
