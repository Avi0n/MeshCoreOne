package one.meshcore.shared.chat.translation

import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual object PlatformMessageTranslation {
    actual suspend fun identify(text: String): Pair<String, Double>? {
        val client = try {
            LanguageIdentification.getClient()
        } catch (_: Throwable) {
            return null
        }
        return try {
            val top = client.identifyPossibleLanguages(text)
                .awaitTask()
                .firstOrNull()
                ?.takeIf { it.languageTag != MessageLanguageDetector.UNDETERMINED_TAG }
            top?.let { it.languageTag to it.confidence.toDouble() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        } finally {
            client.close()
        }
    }

    actual suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        val source = TranslateLanguage.fromLanguageTag(sourceLang)
            ?: throw IllegalArgumentException("unsupported source language $sourceLang")
        val target = TranslateLanguage.fromLanguageTag(targetLang)
            ?: throw IllegalArgumentException("unsupported target language $targetLang")
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        try {
            translator.downloadModelIfNeeded().awaitTask()
            return translator.translate(text).awaitTask()
        } finally {
            translator.close()
        }
    }
}

actual fun platformPreferredLanguage(): String = java.util.Locale.getDefault().language

@Suppress("RedundantSuspendModifier")
private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { value ->
        if (cont.isActive) cont.resume(value)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
}
