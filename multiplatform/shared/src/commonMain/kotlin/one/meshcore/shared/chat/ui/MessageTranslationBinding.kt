package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.translation.MessageTranslationController
import one.meshcore.shared.settings.TranslationTargetPreferences

/** The translation session a chat thread or room transcript reads while it is on screen. */
internal data class MessageTranslationBinding(
    val controller: MessageTranslationController,
    val revision: Int,
    val preferences: TranslationTargetPreferences,
)
