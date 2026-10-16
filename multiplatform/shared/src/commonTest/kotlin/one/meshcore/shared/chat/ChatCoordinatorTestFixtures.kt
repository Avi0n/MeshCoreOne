package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import one.meshcore.shared.model.Uuid

/** Builds a coordinator confined to the test scheduler, with the off-thread build routed there too. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.makeCoordinator(
    store: ChatCoordinatorStore = FakeCoordinatorStore(),
    conversationID: ChatConversationID = ChatConversationID.dm(ChatFixtures.RADIO, ChatFixtures.CONTACT),
): ChatCoordinator = ChatCoordinator(
    conversationID = conversationID,
    store = store,
    scope = backgroundScope,
    computeDispatcher = StandardTestDispatcher(testScheduler),
)

/** Minimal build inputs for a single message; enough to drive [MessageFragmentBuilder] in coordinator tests. */
internal fun buildInputs(id: Uuid): MessageBuildInputs = MessageBuildInputs(
    messageID = id,
    baseColor = BaseColorSlot.INCOMING,
    senderResolution = NodeNameResolution(displayName = "Sender", matchKind = NodeNameMatchKind.EXACT),
    showTimestamp = false,
    showDirectionGap = false,
    showSenderName = false,
    showNewMessagesDivider = false,
)
