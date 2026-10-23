// PortedFrom: MC1/Views/Chats/Components/UnifiedMessageBubble.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MessageBubbleConfiguration.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/BubbleFooterRow.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import android.content.res.Resources
import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.container.ChatMapNetwork
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.l10n.R as L10nR
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.services.rendering.*
import com.meshcoreone.android.core.ui.UiFormatArgument
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.actions.MessagePathFormatter
import com.meshcoreone.android.feature.chats.actions.PathNodeResolver

@Composable
internal fun rememberMessageBubbleLabels(
    content: AppChatContent?,
    contacts: List<ContactDTO>,
    currentUserName: String?,
): ((MessageDTO) -> String)? {
    // An unbound, standalone graph retains the basic timeline label; never synthesize rendering preferences.
    if (content == null) return null
    val preferences by content.rendering.collectAsStateWithLifecycle()
    val network by content.mapPreviews.network.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val frame = LocalMeshTheme.current.frame
    val fontScale = LocalDensity.current.fontScale
    val env = EnvInputs(
        autoPlayGIFs = preferences[AppStorageKey.autoPlayGIFs],
        showIncomingPath = preferences[AppStorageKey.showIncomingPath],
        showIncomingHopCount = preferences[AppStorageKey.showIncomingHopCount],
        showIncomingRegion = preferences[AppStorageKey.showIncomingRegion],
        showIncomingHeardCount = preferences[AppStorageKey.showIncomingHeardCount],
        showIncomingSendTime = preferences[AppStorageKey.showIncomingSendTime],
        previewsEnabled = preferences[AppStorageKey.linkPreviewsEnabled],
        isHighContrast = frame.highContrast,
        isDark = frame.colorScheme == ColorScheme.DARK,
        showMapPreviews = preferences[AppStorageKey.showMapPreviewThumbnails],
        isOffline = network != ChatMapNetwork.Online,
        currentUserName = currentUserName.orEmpty(),
        themeID = frame.theme.id.rawValue,
        contentSizeCategory = fontScale.toString(),
        preferredLanguageCode = resources.configuration.locales[0].language,
        translationOffersEnabled = false,
    )
    val nicknames = remember(contacts) {
        MessageBubbleConfiguration.buildNicknameLookup(contacts)
    }
    return { message ->
        val resolution = bubbleSenderResolution(message, contacts, nicknames, resources)
        val item = messageBubbleAccessibilityItem(message, resolution, env, resources)
        messageBubbleAccessibilityLabel(message, item, message.isChannelMessage, resources)
    }
}

internal fun bubbleSenderResolution(
    message: MessageDTO,
    contacts: List<ContactDTO>,
    nicknames: Map<String, String>,
    resources: Resources,
): NodeNameResolution {
    val unknown = resources.getString(AppChatsStrings.chatsMessageSenderUnknown)
    if (!message.isChannelMessage) return NodeNameResolution(unknown, NodeNameMatchKind.UNRESOLVED)
    message.senderNodeName?.takeIf { it.isNotEmpty() }?.let { name ->
        return NodeNameResolution(name, NodeNameMatchKind.EXACT, nicknames[MessageBubbleConfiguration.loweredName(name)])
    }
    val prefix = message.senderKeyPrefix
    val unresolved = prefix?.takeIf { it.size >= 2 }?.prefix(2)?.uppercaseHexString() ?: unknown
    val resolved = PathNodeResolver.resolution(prefix, contacts, emptyList(), null, unresolved)
    val matchKind = when (resolved.matchKind) {
        com.meshcoreone.android.feature.chats.actions.NodeNameMatchKind.EXACT -> NodeNameMatchKind.EXACT
        com.meshcoreone.android.feature.chats.actions.NodeNameMatchKind.FALLBACK -> NodeNameMatchKind.FALLBACK
        com.meshcoreone.android.feature.chats.actions.NodeNameMatchKind.UNRESOLVED -> NodeNameMatchKind.UNRESOLVED
    }
    return NodeNameResolution(resolved.displayName, matchKind)
}

internal fun messageBubbleAccessibilityItem(
    message: MessageDTO,
    resolution: NodeNameResolution,
    env: EnvInputs,
    resources: Resources,
): MessageItem = MessageFragmentBuilder.makeItem(
    message,
    MessageBuildInputs(
        messageID = message.id,
        // The rich body/media keep their own existing renderer and references. This projection consumes
        // only the real envelope/footer, never these unused content-fragment or preview-fetch fields.
        previewState = PreviewLoadState.IDLE, loadedPreview = null, cachedURL = null,
        isInlineImageURL = false, hasInlineImageRef = false, hasPreviewImageRef = false,
        hasPreviewIconRef = false, imageIsGIF = false, formattedText = null,
        baseColor = if (message.isOutgoing) BaseColorSlot.OUTGOING else BaseColorSlot.INCOMING,
        formattedPath = if (env.showIncomingPath && !message.isOutgoing) MessagePathFormatter.format(
            message, resources.getString(AppChatsStrings.chatsMessagePathDirect),
            resources.getString(AppChatsStrings.chatsMessagePathFlood),
        ) else null,
        senderResolution = resolution, showTimestamp = false, showDirectionGap = false,
        showSenderName = message.isChannelMessage && !message.isOutgoing, showNewMessagesDivider = false,
    ),
    env,
)

internal fun messageBubbleAccessibilityLabel(
    message: MessageDTO,
    item: MessageItem,
    showSenderName: Boolean,
    resources: Resources,
): String = buildString {
    val envelope = item.envelope
    val footer = item.footer
    if (!envelope.isOutgoing && showSenderName) {
        val resolution = envelope.senderResolution
        val nickname = resolution.unverifiedNickname
        if (nickname != null) {
            append(nickname).append(' ')
            append(AppChatsStrings.chatsMessageSenderUnverifiedNicknameFormat(resources, envelope.senderName))
            append(": ").append(resources.getString(AppChatsStrings.chatsMessageSenderUnverifiedNicknameAccessibilityLabel))
            append(", ")
        } else {
            append(envelope.senderName).append(": ")
            if (resolution.isFallback) append(resources.getString(AppChatsStrings.chatsMessageSenderPossibleMatch)).append(", ")
        }
    }
    append(message.text)
    if (envelope.isOutgoing) {
        append(", ").append(bubbleStatusText(footer, resources))
    } else {
        if (footer.showHop) append(", ").append(integerText(
            resources, L10nR.string.l10n_app_chats_chats_message_hopcount_accessibilitylabel, footer.hopCount,
        ))
        footer.formattedPath?.let { append(", ").append(AppChatsStrings.chatsMessagePathAccessibilityLabel(resources, it)) }
        footer.regionToShow?.let { region ->
            append(", ").append(if (footer.regionMatchNames.size > 1) {
                AppChatsStrings.chatsMessageRegionAmbiguousAccessibilityLabel(
                    resources, ListFormatter.getInstance(resources.configuration.locales[0]).format(footer.regionMatchNames),
                )
            } else AppChatsStrings.chatsMessageRegionAccessibilityLabel(resources, region))
        }
    }
}

private fun bubbleStatusText(footer: MessageFooter, resources: Resources): String {
    fun string(id: Int) = resources.getString(id)
    fun sent() = if (footer.sendCount > 1) integerText(
        resources, L10nR.string.l10n_app_chats_chats_message_status_sentmultiple, footer.sendCount,
    ) else string(AppChatsStrings.chatsMessageStatusSent)
    fun repeats() = "${footer.heardRepeats} " + string(if (footer.heardRepeats == 1L) {
        AppChatsStrings.chatsMessageRepeatSingular
    } else AppChatsStrings.chatsMessageRepeatPlural)
    return when (footer.status) {
        MessageStatus.PENDING, MessageStatus.SENDING -> string(AppChatsStrings.chatsMessageStatusSending)
        MessageStatus.SENT -> if (!footer.isChannelMessage) string(AppChatsStrings.chatsMessageStatusSending) else
            buildList { if (footer.heardRepeats > 0) add(repeats()); add(sent()) }.joinToString(" \u2022 ")
        MessageStatus.DELIVERED -> buildList {
            if (footer.heardRepeats > 0) add(repeats())
            add(string(AppChatsStrings.chatsMessageStatusDelivered))
            if (footer.sendCount > 1) add(sent())
        }.joinToString(" \u2022 ")
        MessageStatus.FAILED -> string(AppChatsStrings.chatsMessageStatusFailed)
        MessageStatus.RETRYING -> if (footer.maxRetryAttempts > 0) integerText(
            resources, L10nR.string.l10n_app_chats_chats_message_status_retryingattempt,
            Math.addExact(footer.retryAttempt, 1), footer.maxRetryAttempts,
        ) else string(AppChatsStrings.chatsMessageStatusRetrying)
    }
}

private fun integerText(resources: Resources, id: Int, vararg values: Long): String =
    UiText.Format(id, values.map { UiFormatArgument.Integer(it) }.snapshot()).resolve(resources)
