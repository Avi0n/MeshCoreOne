package one.meshcore.shared.navigation

import one.meshcore.shared.chat.text.HashtagDeeplink
import one.meshcore.shared.model.toUppercaseHex

/**
 * The connection-derived state a `meshcore://` contact or channel link is matched against: the connected
 * radio's own key (adding yourself is a no-op) and the identity sets of records already stored for the
 * current radio. Supplied by the app layer, which reads them from the live connection or the offline
 * store; `null`/empty when disconnected. Reads are suspend so the app can hit the store lazily per link.
 */
interface DeepLinkLookups {
    suspend fun ownPublicKey(): ByteArray?

    suspend fun existingContactKeyHexes(): Set<String>

    suspend fun existingChannelSecretHexes(): Set<String>

    /** Local contact row id for [publicKeyHex], or null when not stored. */
    suspend fun contactIdForPublicKeyHex(publicKeyHex: String): String? = null

    /** Local channel row id for [secretHex], or null when not stored. */
    suspend fun channelIdForSecretHex(secretHex: String): String? = null

    /** Local channel row id whose name equals [fullChannelName] ignoring case, or null when not stored. */
    suspend fun channelIdForName(fullChannelName: String): String? = null
}

/**
 * Dispatches a `meshcore://` URL onto the [NavigationCoordinator], the Kotlin analogue of the iOS
 * `ChatLinkRouter`. Parsing and the pure match rules live in [MeshCoreUrlParser] and [ChatLinkRouter];
 * this layer performs the resulting action against the coordinator and the store.
 *
 * [routeExternalOpen] is the entry point for a URL the OS hands the app from outside a chat (a scanned QR
 * code or another app): it switches to the Chats tab first so the add-contact / join-channel confirmation
 * sheets, which only present from Chats, have their host, and restores the prior tab when nothing matched.
 */
class DeepLinkRouter(private val coordinator: NavigationCoordinator, private val lookups: DeepLinkLookups) {
    /**
     * Routes [url], returning whether the router claimed it. A map link centers the Map tab; a contact or
     * channel link opens the existing record or stages a confirmation; a hashtag link opens the joined
     * channel or stages the join; a send or advert URL stages the Shortcuts action; a bare
     * `meshcore://<tab>` shortcut URL selects that place in the app.
     * Returns false for an unparseable `meshcore://` URL or a foreign scheme so the caller can fall through
     * to the system handler.
     *
     * The content parses run first: `meshcore://map?lat=&lon=` is a map focus, and only a bare
     * `meshcore://map` falls through to the shortcut branch.
     */
    suspend fun route(url: String): Boolean {
        if (routeParsedLinks(url)) return true
        if (routeStagedActions(url)) return true
        return false
    }

    private suspend fun routeParsedLinks(url: String): Boolean {
        suspend fun map(): Boolean? {
            val parsed = MeshCoreUrlParser.parseMapUrl(url) ?: return null
            coordinator.navigateToMap(parsed.latitude, parsed.longitude)
            return true
        }
        suspend fun contact(): Boolean? {
            val parsed = MeshCoreUrlParser.parseContactUrl(url) ?: return null
            handleContact(parsed)
            return true
        }
        suspend fun channel(): Boolean? {
            val parsed = MeshCoreUrlParser.parseChannelUrl(url) ?: return null
            handleChannel(parsed)
            return true
        }
        return map() ?: contact() ?: channel() ?: false
    }

    private suspend fun routeStagedActions(url: String): Boolean {
        suspend fun send(): Boolean? {
            val parsed = MeshCoreUrlParser.parseSendMessageUrl(url) ?: return null
            coordinator.stageSendMessage(parsed)
            return true
        }
        suspend fun advert(): Boolean? {
            val parsed = MeshCoreUrlParser.parseSendAdvertUrl(url) ?: return null
            coordinator.stageSendAdvert(parsed)
            return true
        }
        suspend fun hashtag(): Boolean? {
            val rawName = HashtagDeeplink.channelNameFromUrl(url) ?: return null
            handleHashtag(rawName)
            return true
        }
        fun shortcut(): Boolean? {
            val destination = AppShortcutDestination.parse(url) ?: return null
            openShortcutDestination(destination)
            return true
        }
        return send() ?: advert() ?: hashtag() ?: shortcut() ?: false
    }

    /**
     * Routes a URL handed to the app from outside a chat. Switches to Chats first so the confirmation
     * sheets can present, then restores the previous tab if nothing was handled.
     */
    suspend fun routeExternalOpen(url: String): Boolean {
        val previousTab = AppTab.fromIndex(coordinator.selectedTab.value)
        coordinator.selectTab(AppTab.CHATS)
        val handled = route(url)
        if (!handled) coordinator.selectTab(previousTab)
        return handled
    }

    private suspend fun handleContact(contact: MeshCoreDeepLink.Contact) {
        when (
            val action = ChatLinkRouter.routeContact(
                contact = contact,
                ownPublicKey = lookups.ownPublicKey(),
                existingContactKeyHexes = lookups.existingContactKeyHexes(),
            )
        ) {
            is ChatLinkRoute.Ignore -> Unit
            is ChatLinkRoute.OpenContactDetail -> {
                val keyHex = action.contact.publicKey.toUppercaseHex()
                val localId = lookups.contactIdForPublicKeyHex(keyHex) ?: keyHex
                coordinator.navigateToContactDetail(localId)
            }
            is ChatLinkRoute.ConfirmAddContact -> coordinator.stageContactLink(action.contact)
            else -> Unit
        }
    }

    /**
     * Routes a `meshcoreone://hashtag/<name>` link: an already-joined channel of that name opens, anything
     * else stages the join confirmation. An invalid hashtag body is dropped, as in iOS `handleHashtagTap`.
     */
    private suspend fun handleHashtag(rawChannelName: String) {
        val fullName = HashtagDeeplink.fullChannelName(rawChannelName) ?: return
        val localId = lookups.channelIdForName(fullName)
        if (localId != null) coordinator.navigateToChannel(localId) else coordinator.stageHashtagLink(rawChannelName)
    }

    private fun openShortcutDestination(destination: AppShortcutDestination) = when (destination) {
        AppShortcutDestination.CHATS -> coordinator.selectTab(AppTab.CHATS)
        AppShortcutDestination.MAP -> coordinator.selectTab(AppTab.MAP)
        AppShortcutDestination.RADIO_STATUS -> coordinator.navigateToSettings(SettingsDestination.DEVICE_INFO)
    }

    private suspend fun handleChannel(channel: MeshCoreDeepLink.Channel) {
        when (val action = ChatLinkRouter.routeChannel(channel, lookups.existingChannelSecretHexes())) {
            is ChatLinkRoute.OpenChannel -> {
                val localId = lookups.channelIdForSecretHex(action.channel.id) ?: action.channel.id
                coordinator.navigateToChannel(localId)
            }
            is ChatLinkRoute.ConfirmJoinChannel -> coordinator.stageChannelLink(action.channel)
            else -> Unit
        }
    }
}
