package one.meshcore.shared.chat.translation

import android.os.LocaleList
import com.google.mlkit.nl.translate.TranslateLanguage

actual fun platformOnDeviceTranslationCatalog(): List<String> = TranslateLanguage.getAllLanguages()

actual fun platformPreferredLanguageTags(): List<String> {
    val list = LocaleList.getDefault()
    return (0 until list.size()).map { list[it].toLanguageTag() }
}
