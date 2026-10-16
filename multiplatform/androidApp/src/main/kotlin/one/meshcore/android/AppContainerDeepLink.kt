package one.meshcore.android

import android.content.Intent
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.DeepLinkLookups
import one.meshcore.shared.navigation.DeepLinkRouter
import one.meshcore.shared.navigation.NotificationConversationOpen

/** Routes a deep link the OS delivered while the app is already running (warm launch / QR scan). */
fun AppContainer.submitDeepLink(url: String) = pendingExternalUrl.submit(url)

/**
 * Routes a notification-tap conversation open. A warm tap opens immediately; a cold-launch tap is
 * staged and drained once launch settles (parallel to [submitDeepLink]).
 */
fun AppContainer.submitConversationOpen(conversationKey: String, messageId: String? = null) =
    pendingConversationOpen.submit(NotificationConversationOpen(conversationKey, messageId))

/**
 * Routes a new-contact-discovery notification tap to the contact's detail. A warm tap opens
 * immediately; a cold-launch tap is staged and drained once launch settles (parallel to
 * [submitConversationOpen]).
 */
fun AppContainer.submitContactOpen(contactId: String) = pendingContactOpen.submit(contactId)

/** Opens a plain web URL (a link tapped in a message) in the platform browser. */
fun AppContainer.openExternalUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { appContext.startActivity(intent) }
}
internal fun AppContainer.routeExternal(url: String) {
    appScope.launch { deepLinkRouter.routeExternalOpen(url) }
}
internal fun AppContainer.buildDeepLinkRouter() = DeepLinkRouter(
    coordinator = coordinator,
    lookups = object : DeepLinkLookups {
        override suspend fun ownPublicKey(): ByteArray? = connectionManager.services?.selfPublicKey

        override suspend fun existingContactKeyHexes(): Set<String> {
            val radio = currentRadioId() ?: return emptySet()
            val contacts = contactService()?.getContacts(radio).orEmpty()
            return contacts.map { it.publicKey.toUppercaseHex() }.toSet()
        }

        override suspend fun existingChannelSecretHexes(): Set<String> {
            val radio = currentRadioId() ?: return emptySet()
            val channels = conversationListStore()?.fetchChannels(radio).orEmpty()
            return channels.map { it.secret.toUppercaseHex() }.toSet()
        }

        override suspend fun contactIdForPublicKeyHex(publicKeyHex: String): String? {
            val radio = currentRadioId() ?: return null
            return contactService()?.getContacts(radio)
                ?.firstOrNull { it.publicKey.toUppercaseHex() == publicKeyHex }
                ?.id?.value
        }

        override suspend fun channelIdForSecretHex(secretHex: String): String? {
            val radio = currentRadioId() ?: return null
            return conversationListStore()?.fetchChannels(radio)
                ?.firstOrNull { it.secret.toUppercaseHex() == secretHex }
                ?.id?.value
        }

        // Case-insensitive, matching the iOS hashtag lookup's localizedCaseInsensitiveCompare.
        override suspend fun channelIdForName(fullChannelName: String): String? {
            val radio = currentRadioId() ?: return null
            return conversationListStore()?.fetchChannels(radio)
                ?.firstOrNull { it.name.equals(fullChannelName, ignoreCase = true) }
                ?.id?.value
        }
    },
)
