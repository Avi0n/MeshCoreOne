// AndroidOnly: WP-407 Native per-app locales on API 33+ and configuration-context selection on API 31-32.
package com.meshcoreone.android.app.platform

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

internal object AppLanguages {
    val supported = listOf("en", "de", "es", "fr", "it", "ko", "nl", "pl", "pt-PT", "ru", "uk", "zh-Hans")
    private const val KEY = "language"
    private fun preferences(context: Context) = context.getSharedPreferences("app_language", Context.MODE_PRIVATE)
    fun selected(context: Context): String = if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags()
    } else preferences(context).getString(KEY, "").orEmpty()

    fun attach(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) {
            val stored = preferences(context)
            if (!stored.getBoolean("native_migrated", false)) {
                val manager = context.getSystemService(LocaleManager::class.java)
                val previous = stored.getString(KEY, "").orEmpty()
                if (manager.applicationLocales.isEmpty && previous in supported) {
                    manager.applicationLocales = LocaleList.forLanguageTags(previous)
                }
                check(stored.edit().putBoolean("native_migrated", true).commit()) { "Could not persist locale migration" }
            }
            return context
        }
        val tag = selected(context)
        if (tag.isEmpty()) return context
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(config)
    }
    fun select(activity: Activity, tag: String) {
        require(tag.isEmpty() || tag in supported) { "Unsupported app locale" }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            check(preferences(activity).edit().putString(KEY, tag).commit()) { "Could not save app locale" }
            activity.recreate()
        }
    }
}
