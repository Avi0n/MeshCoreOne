// PortedFrom: MC1Tests/ViewModels/NodeConfigImportViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.platform

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.feature.settings.app.nodeconfig.ResourceNodeConfigMessages
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class)
class NodeConfigMessagesTest {
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<android.app.Application>().resources
    private val messages get() = ResourceNodeConfigMessages(resources)
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
}
