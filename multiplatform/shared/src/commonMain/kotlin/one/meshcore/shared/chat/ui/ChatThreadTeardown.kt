package one.meshcore.shared.chat.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.chat.ChatThreadViewModel
import one.meshcore.shared.chat.ComposerViewModel
import one.meshcore.shared.chat.cleanup
import one.meshcore.shared.chat.notifyConversationsChanged

/**
 * The thread teardown. Nothing here may run on the composable's own scope: Compose cancels that scope in
 * the same apply pass that runs `onDispose`, so a draft flushed there is lost and — worse — the
 * active-conversation claim is never released, leaving that conversation with no banner and no unread bump
 * for as long as the app stays foregrounded. [ChatThreadViewModel.cleanup] releases the claim through a
 * sink that owns a longer-lived scope; the draft persist runs on [teardownScope] for the same reason.
 * Swift `ChatConversationView.onDisappear` runs both halves synchronously.
 */
internal fun disposeChatThread(
    teardownScope: CoroutineScope,
    composer: ComposerViewModel,
    viewModel: ChatThreadViewModel,
) {
    composer.close()
    teardownScope.launch {
        composer.flushDraft()
        viewModel.notifyConversationsChanged()
    }
    viewModel.cleanup()
}
