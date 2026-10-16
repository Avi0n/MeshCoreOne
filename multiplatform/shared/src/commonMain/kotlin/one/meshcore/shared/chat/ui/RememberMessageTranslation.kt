package one.meshcore.shared.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.chat.translation.MessageTranslationController
import one.meshcore.shared.chat.translation.PlatformMessageTranslation
import one.meshcore.shared.chat.translation.platformPreferredLanguage
import one.meshcore.shared.settings.TranslationTargetPreferences

/**
 * Holds one [MessageTranslationController] for [rememberKey], reading language and offer toggles from
 * [preferences] (or an empty stand-in). Cancels an in-flight translation when the caller leaves
 * composition, and drops cached translations when the preferred language changes.
 */
@Composable
internal fun rememberMessageTranslation(
    rememberKey: Any?,
    preferences: StateFlow<TranslationTargetPreferences>?,
): MessageTranslationBinding {
    val fallback = remember { MutableStateFlow(TranslationTargetPreferences()) }
    val translationPrefs by stateOrFallback(preferences, fallback).collectAsStateWithLifecycle()
    val preferredLanguage = translationPrefs.resolvedLanguageCode(platformPreferredLanguage())
    val preferredLanguageHolder = remember { mutableStateOf(preferredLanguage) }
    preferredLanguageHolder.value = preferredLanguage
    val offersHolder = remember { mutableStateOf(translationPrefs.translationOffersEnabled) }
    offersHolder.value = translationPrefs.translationOffersEnabled
    val controller = remember(rememberKey) {
        MessageTranslationController(
            identify = PlatformMessageTranslation::identify,
            translate = PlatformMessageTranslation::translate,
            preferredLanguage = { preferredLanguageHolder.value },
            offersEnabled = { offersHolder.value },
        )
    }
    val revision by controller.revision.collectAsStateWithLifecycle()
    LaunchedEffect(controller) {
        try {
            awaitCancellation()
        } finally {
            controller.cancelPendingTranslation()
        }
    }
    LaunchedEffect(preferredLanguage, controller) {
        controller.invalidateTranslations(preferredLanguage)
    }
    return MessageTranslationBinding(
        controller = controller,
        revision = revision,
        preferences = translationPrefs,
    )
}
