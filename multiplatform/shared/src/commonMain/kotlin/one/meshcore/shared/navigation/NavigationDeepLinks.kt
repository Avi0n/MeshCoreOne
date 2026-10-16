package one.meshcore.shared.navigation

/** Stages the add-contact confirmation for [contact]. */
fun NavigationCoordinator.stageContactLink(contact: MeshCoreDeepLink.Contact) {
    pendingContactLinkState.value = contact
}

/** Stages the join-channel confirmation for [channel]. */
fun NavigationCoordinator.stageChannelLink(channel: MeshCoreDeepLink.Channel) {
    pendingChannelLinkState.value = channel
}

/** Stages the join-from-message confirmation for the hashtag channel [rawChannelName]. */
fun NavigationCoordinator.stageHashtagLink(rawChannelName: String) {
    pendingHashtagState.value = rawChannelName
}

/** Stages a Shortcuts send. The shell enqueues it or escalates if the radio is not ready. */
fun NavigationCoordinator.stageSendMessage(request: MeshCoreDeepLink.SendMessage) {
    pendingSendMessageState.value = request
}

fun NavigationCoordinator.consumeSendMessage() {
    pendingSendMessageState.value = null
}

/** Stages a Shortcuts self-advert. The shell transmits it with no location prompt. */
fun NavigationCoordinator.stageSendAdvert(request: MeshCoreDeepLink.SendAdvert) {
    pendingSendAdvertState.value = request
}

fun NavigationCoordinator.consumeSendAdvert() {
    pendingSendAdvertState.value = null
}

fun NavigationCoordinator.clearPendingMapFocus() {
    pendingMapFocusState.value = null
}

fun NavigationCoordinator.setDroppedMapPin(latitude: Double, longitude: Double) {
    droppedMapPinState.value = MapFocusRequest(latitude, longitude)
}
