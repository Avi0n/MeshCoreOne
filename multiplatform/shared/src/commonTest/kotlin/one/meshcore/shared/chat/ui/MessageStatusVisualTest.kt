package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.MessageFooter
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.ui.icons.AppIcons
import kotlin.test.Test
import kotlin.test.assertEquals

class MessageStatusVisualTest {
    @Test
    fun inFlightStatusesUseASpinner() {
        assertEquals(StatusVisualKind.SPINNER, statusVisualKind(footer(MessageStatus.PENDING, isChannel = true)))
        assertEquals(StatusVisualKind.SPINNER, statusVisualKind(footer(MessageStatus.SENDING, isChannel = true)))
        assertEquals(StatusVisualKind.SPINNER, statusVisualKind(footer(MessageStatus.RETRYING, isChannel = true)))
        assertEquals(StatusVisualKind.SPINNER, statusVisualKind(footer(MessageStatus.SENT, isChannel = false)))
    }

    @Test
    fun terminalSuccessUsesAFilledCheck() {
        assertEquals(StatusVisualKind.CHECK, statusVisualKind(footer(MessageStatus.SENT, isChannel = true)))
        assertEquals(StatusVisualKind.CHECK, statusVisualKind(footer(MessageStatus.DELIVERED, isChannel = false)))
        assertEquals(AppIcons.CheckCircle, STATUS_SUCCESS_ICON)
    }

    @Test
    fun failedUsesARefreshControl() {
        assertEquals(StatusVisualKind.RETRY, statusVisualKind(footer(MessageStatus.FAILED, isChannel = true)))
        assertEquals(AppIcons.Refresh, STATUS_RETRY_ICON)
        assertEquals(STATUS_ICON_DP, STATUS_RETRY_LAYOUT_DP)
    }

    @Test
    fun statusGlyphCrossfadeMatchesIosInPlaceUpdate() {
        assertEquals(300, MESSAGE_STATUS_CROSSFADE_MS)
    }

    @Test
    fun visibleRetryCounterIsOneBased() {
        assertEquals(1, retryDisplayAttempt(0))
        assertEquals(3, retryDisplayAttempt(2))
        assertEquals("1/5", visibleRetryAttemptText(footer(MessageStatus.RETRYING, isChannel = true, maxRetry = 5)))
        assertEquals(null, visibleRetryAttemptText(footer(MessageStatus.SENDING, isChannel = true, maxRetry = 5)))
    }

    private fun footer(status: MessageStatus, isChannel: Boolean, maxRetry: Int = 0) = MessageFooter(
        showStatusRow = true,
        status = status,
        isChannelMessage = isChannel,
        heardRepeats = 0,
        retryAttempt = 0,
        maxRetryAttempts = maxRetry,
        sendCount = 1,
        sendTimeToShowEpochSeconds = null,
        sendTimeWasCorrected = false,
        showHop = false,
        hopCount = 0,
        formattedPath = null,
        regionToShow = null,
    )
}
