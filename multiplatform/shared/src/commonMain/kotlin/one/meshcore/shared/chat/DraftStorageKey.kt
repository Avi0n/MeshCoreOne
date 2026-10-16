package one.meshcore.shared.chat

/**
 * The pinned key a per-conversation draft is stored under in [DraftStore]. Encodes the
 * `(radio, conversation)` pair as `{radioID}|dm|{contactID}` for a direct chat and
 * `{radioID}|ch|{channelIndex}` for a channel, matching the iOS `ChatConversationID.draftStorageKey`
 * character-for-character so both platforms agree on the key format the shifted-storage tests freeze.
 *
 * [one.meshcore.shared.model.Uuid] is already uppercase-normalized, so the radio and contact segments
 * are canonical uppercase without extra work. The format is a stored contract: a change here silently
 * strands every existing draft under its old key, so it is pinned by [DraftStorageKeyTest].
 */
val ChatConversationID.draftStorageKey: String
    get() = when (val key = conversation) {
        is ChatConversationID.ConversationKey.Dm -> "${radioID.value}|dm|${key.contactID.value}"
        is ChatConversationID.ConversationKey.Channel -> "${radioID.value}|ch|${key.channelIndex}"
    }
