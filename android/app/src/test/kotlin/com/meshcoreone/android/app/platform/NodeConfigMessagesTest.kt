// PortedFrom: MC1Tests/ViewModels/NodeConfigImportViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import android.content.res.Resources
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.services.remote.NodeConfigContactSaveFailure
import com.meshcoreone.android.feature.settings.R
import com.meshcoreone.android.feature.settings.app.nodeconfig.ResourceNodeConfigMessages
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class NodeConfigMessagesTest {
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources
    private fun messagesFor(resources: Resources) =
        ResourceNodeConfigMessages(resources, { it is NodeConfigContactSaveFailure })
    private val messages get() = messagesFor(resources)
    private class Fault(override val sourceServiceFault: SourceServiceFault) : Exception(), SourceServiceFaultCarrier

    @Test
    @NodeConfigOriginalCase("NodeConfigImportViewModelTests::A radio-out-of-range error maps to the localized field label and template")
    fun radioRange() {
        assertEquals(S.configImportErrorRadioOutOfRange(resources, resources.getString(S.configImportFieldFrequency)),
            messages.error(Fault(NodeConfigServiceFault.InvalidRadioSettings(NodeConfigRadioField.FREQUENCY))))
    }

    @Test
    @NodeConfigOriginalCase("NodeConfigImportViewModelTests::A position-coordinate error maps to the localized position template")
    fun positionCoordinate() {
        assertEquals(S.configImportErrorPositionInvalid(resources, resources.getString(S.configImportFieldLatitude)),
            messages.error(Fault(NodeConfigServiceFault.InvalidCoordinate(NodeConfigCoordinateField.PositionLatitude))))
    }

    @Test
    @NodeConfigOriginalCase("NodeConfigImportViewModelTests::A contact-coordinate error maps to the localized contact template with the contact name")
    fun contactCoordinate() {
        assertEquals(S.configImportErrorContactCoordinateInvalid(resources, "C1", resources.getString(S.configImportFieldLongitude)),
            messages.error(Fault(NodeConfigServiceFault.InvalidCoordinate(NodeConfigCoordinateField.ContactLongitude("C1")))))
    }

    @Test
    @NodeConfigOriginalCase("NodeConfigImportViewModelTests::A non-NodeConfigServiceError falls through to its localizedDescription")
    fun genericErrorUsesExistingMapperFallback() {
        assertEquals("Synthetic timed out", messages.error(java.io.IOException("Synthetic timed out")))
    }

    @Test
    @NodeConfigOriginalCase("NodeConfigImportViewModelTests::An invalid-out-path error maps to the localized template with the contact name")
    fun invalidPath() {
        val text = messages.error(Fault(NodeConfigServiceFault.InvalidOutPath("X")))
        assertEquals(S.configImportErrorInvalidOutPath(resources, "X"), text)
        assertTrue(text.contains("X"))
    }

    @Test
    @NodeConfigOriginalCase("NodeConfigImportViewModelTests::A contact-capacity error maps to the localized template with the needed and available counts")
    fun capacity() {
        assertEquals(S.configImportErrorContactCapacityExceeded(resources, 3, 1),
            messages.error(Fault(NodeConfigServiceFault.ContactCapacityExceeded(3, 1))))
    }

    @Target(AnnotationTarget.FUNCTION)
    @Retention(AnnotationRetention.SOURCE)
    private annotation class NodeConfigOriginalCase(val value: String)

    @Test fun cancellationAndPartialFailureUseActualLocalizedResources() {
        assertEquals(resources.getString(S.configImportCancelled), messages.cancelled(false))
        assertEquals(resources.getString(S.configImportCancelledPartial), messages.cancelled(true))
        val failure = java.io.IOException("Synthetic failure")
        assertEquals(messages.error(failure), messages.failed(failure, false))
        assertEquals(S.configImportFailedPartial(resources, messages.error(failure)), messages.failed(failure, true))
    }

    @Test fun actualContactSaveFailureUsesLocalizedRecoveryForEverySupportedLocale() {
        val expected = mapOf(
            "en" to "The radio accepted the contact, but it could not be saved locally. Retry the import.",
            "de" to "Das Funkgerät hat den Kontakt angenommen, aber er konnte nicht lokal gespeichert werden. Versuche den Import erneut.",
            "es" to "La radio aceptó el contacto, pero no se pudo guardar localmente. Vuelve a intentar la importación.",
            "fr" to "La radio a accepté le contact, mais il n’a pas pu être enregistré localement. Réessayez l’importation.",
            "it" to "La radio ha accettato il contatto, ma non è stato possibile salvarlo in locale. Riprova l’importazione.",
            "ko" to "무전기가 연락처를 수락했지만 로컬에 저장하지 못했습니다. 가져오기를 다시 시도하세요.",
            "nl" to "De radio heeft het contact geaccepteerd, maar het kon niet lokaal worden opgeslagen. Probeer het importeren opnieuw.",
            "pl" to "Radio przyjęło kontakt, ale nie udało się go zapisać lokalnie. Ponów importowanie.",
            "pt" to "O rádio aceitou o contacto, mas não foi possível guardá-lo localmente. Tente importar novamente.",
            "ru" to "Радиоустройство приняло контакт, но не удалось сохранить его локально. Повторите импорт.",
            "uk" to "Радіопристрій прийняв контакт, але не вдалося зберегти його локально. Повторіть імпорт.",
            "zh-Hans" to "电台已接受该联系人，但无法在本地保存。请重试导入。",
        )
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val failure = NodeConfigContactSaveFailure(IllegalStateException("store"))
        for ((language, copy) in expected) {
            val configuration = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val localized = context.createConfigurationContext(configuration).resources
            val mapped = messagesFor(localized)
            assertEquals(language, copy, localized.getString(R.string.node_config_contact_save_failed))
            assertEquals(language, copy, mapped.failed(failure, false))
            assertEquals(language, S.configImportFailedPartial(localized, copy), mapped.failed(failure, true))
            assertNotEquals(language, copy, mapped.failed(java.io.IOException("Radio error"), false))
            assertEquals(language, localized.getString(S.configImportCancelled), mapped.cancelled(false))
            assertEquals(language, localized.getString(S.configImportCancelledPartial), mapped.cancelled(true))
        }
    }
}
