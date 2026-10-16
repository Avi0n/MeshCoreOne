package one.meshcore.shared.chat

import one.meshcore.shared.EventBroadcaster

/** Status-event multicast. Mirrors the Swift `EventBroadcaster`. */
typealias MessageStatusBroadcaster = EventBroadcaster<MessageStatusEvent>
