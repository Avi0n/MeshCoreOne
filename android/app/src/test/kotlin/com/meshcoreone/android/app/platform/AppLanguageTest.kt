// AndroidOnly: WP-407 Actual native locale preference/configuration on simulated API31/33/37.
package com.meshcoreone.android.app.platform

import android.app.Application
import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33, 37], application = Application::class)
class AppLanguageTest {
    @Test fun savedLocaleUsesConfigurationOrMigratesToNativeLocaleManagerWithoutOverridingSystemChoice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_language", Context.MODE_PRIVATE).edit().clear().putString("language", "de").commit()
        if (Build.VERSION.SDK_INT >= 33) {
            val manager = context.getSystemService(LocaleManager::class.java)
            manager.applicationLocales = LocaleList.getEmptyLocaleList()
            AppLanguages.attach(context)
            assertEquals("de", manager.applicationLocales.toLanguageTags())
            manager.applicationLocales = LocaleList.forLanguageTags("fr")
            AppLanguages.attach(context)
            assertEquals("fr", manager.applicationLocales.toLanguageTags())
        } else {
            val localized = AppLanguages.attach(context)
            assertEquals("de", localized.resources.configuration.locales[0].language)
            assertEquals("de", AppLanguages.selected(context))
        }
        assertEquals(12, AppLanguages.supported.size)
    }
}
