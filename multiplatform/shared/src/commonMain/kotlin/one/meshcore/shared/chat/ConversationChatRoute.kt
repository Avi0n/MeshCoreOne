package one.meshcore.shared.chat

import one.meshcore.shared.navigation.ChatRoute

/** True when this list row is the conversation [route] is showing in the split detail pane. */
fun Conversation.matchesChatRoute(route: ChatRoute): Boolean = when (this) {
    is Conversation.Direct -> route is ChatRoute.Direct && route.contactId == id.value
    is Conversation.Channel -> route is ChatRoute.Channel && route.channelId == id.value
    is Conversation.Room -> route is ChatRoute.Room && route.sessionId == id.value
}
