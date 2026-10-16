package one.meshcore.shared.contact

import one.meshcore.shared.ui.formatTemplate

/**
 * The one-shot screen-reader line iOS posts from `PingHelper` after a zero-hop ping: success names the
 * latency; any failure uses the same "Ping failed" announcement (not the on-screen no-response caption).
 */
internal fun pingAnnouncement(result: ContactPingResult, successTemplate: String, failureMessage: String): String =
    when (result) {
        is ContactPingResult.Success -> formatTemplate(successTemplate, listOf(result.latencyMs))
        is ContactPingResult.Failure, ContactPingResult.NoResponse -> failureMessage
    }
