// PortedFrom: MC1Tests/Intents/IntentErrorLocalizationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-404 Typed payload/cause and actual executor-to-localized-presenter regression.
package com.meshcoreone.android.platform.shortcuts

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.CommandCode
import java.util.Locale
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SessionErrorTest {
    private val locales = listOf("de", "en", "es", "fr", "it", "ko", "nl", "pl", "pt", "ru", "uk", "zh-Hans")

    private fun context(locale: String): Context {
        val app = ApplicationProvider.getApplicationContext<Context>()
        return app.createConfigurationContext(Configuration(app.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
        })
    }

    @Test
    @SourceCases("IntentErrorLocalizationTests::session error recurses into wrapped mesh core error()",
        "IntentErrorLocalizationTests::session error carries no key of its own()")
    fun allSessionCasesUseUnderlyingLocalizedCopyInEveryLocale() {
        for (locale in locales) {
            val context = context(locale)
            val r = context.resources
            val text = AndroidShortcutText(context)
            val cases = listOf(
                MeshCoreException.Timeout() to r.getString(L.errorMeshCoreTimeout),
                MeshCoreException.NotConnected() to r.getString(L.errorMeshCoreNotConnected),
                MeshCoreException.ContactNotFound(Bytes(ByteArray(6))) to r.getString(L.errorMeshCoreContactNotFound),
                MeshCoreException.BluetoothUnavailable() to r.getString(L.errorMeshCoreBluetoothUnavailable),
                MeshCoreException.BluetoothUnauthorized() to r.getString(L.errorMeshCoreBluetoothUnauthorized),
                MeshCoreException.BluetoothPoweredOff() to r.getString(L.errorMeshCoreBluetoothPoweredOff),
                MeshCoreException.SessionNotStarted() to r.getString(L.errorMeshCoreSessionNotStarted),
                MeshCoreException.FeatureDisabled() to r.getString(L.errorMeshCoreFeatureDisabled),
                MeshCoreException.ParseError("payload") to L.errorMeshCoreParseError(r, "payload"),
                MeshCoreException.CommandFailed(CommandCode.SEND_MESSAGE, "busy") to L.errorMeshCoreCommandFailed(r, "busy"),
                MeshCoreException.InvalidResponse("ack", "data") to L.errorMeshCoreInvalidResponse(r, "ack", "data"),
                MeshCoreException.DataTooLarge(150, 151) to L.errorMeshCoreDataTooLarge(r, 151, 150),
                MeshCoreException.SigningFailed("key") to L.errorMeshCoreSigningFailed(r, "key"),
                MeshCoreException.InvalidInput("channel") to L.errorMeshCoreInvalidInput(r, "channel"),
                MeshCoreException.Unknown("detail") to L.errorMeshCoreUnknown(r, "detail"),
                MeshCoreException.ConnectionLost() to r.getString(L.errorMeshCoreConnectionLostNoDetail),
                MeshCoreException.ConnectionLost(MeshCoreException.Timeout()) to
                    L.errorMeshCoreConnectionLost(r, r.getString(L.errorMeshCoreTimeout)),
            )
            for ((underlying, expected) in cases) {
                val error = ShortcutError.SessionError(underlying)
                assertSame(underlying, error.underlying)
                assertEquals(expected, text.error(error), "$locale/${underlying.javaClass.simpleName}")
                assertTrue(expected.isNotBlank())
                assertNotEquals(text.error(ShortcutError.SEND_FAILED), text.error(error))
            }
        }
    }

    @Test
    fun sessionDeviceCodesUseFirmwareCopyIncludingUnknownBoundsInEveryLocale() {
        for (locale in locales) {
            val context = context(locale)
            val r = context.resources
            val text = AndroidShortcutText(context)
            val known = listOf(L.errorDeviceUnsupportedCommand, L.errorDeviceNotFound, L.errorDeviceStorageFull,
                L.errorDeviceInvalidState, L.errorDeviceFileSystem, L.errorDeviceInvalidParameter)
            for ((index, resource) in known.withIndex()) {
                assertEquals(r.getString(resource),
                    text.error(ShortcutError.SessionError(MeshCoreException.DeviceError((index + 1).toUByte()))), locale)
            }
            for (code in listOf(0, 7, 255)) {
                assertEquals(L.errorDeviceUnknown(r, code.toLong()),
                    text.error(ShortcutError.SessionError(MeshCoreException.DeviceError(code.toUByte()))), locale)
            }
        }
    }

    @Test
    fun sessionPayloadAndOuterDiagnosticCauseAreIndependent() {
        val underlying = MeshCoreException.InvalidResponse("ack", "data")
        val payload = ShortcutError.SessionError(underlying)
        assertSame(underlying, ShortcutException(payload).cause)
        val outer = IllegalStateException("service diagnostics", underlying)
        val failure = ShortcutException(payload, outer)
        assertSame(payload, failure.error)
        assertSame(outer, failure.cause)
        assertSame(underlying, payload.underlying)
        assertEquals("ack", underlying.expected)
        assertEquals("data", underlying.got)
    }

    @Test
    fun executorPresentsSessionCopyForBothRecipientsAndAdvertsWithoutLosingPayload() = runSuspend {
        val alice = contact()
        val ops = channel()
        val requests = listOf(
            ShortcutRequest.Send(TargetIdentity.contactId(alice), "hi"),
            ShortcutRequest.Send(TargetIdentity.channelId(ops), "hi"),
            ShortcutRequest.Advert(AdvertReach.ZERO_HOP),
            ShortcutRequest.Advert(AdvertReach.FLOOD),
        )
        for (request in requests) {
            val underlying = MeshCoreException.FeatureDisabled()
            val payload = ShortcutError.SessionError(underlying)
            val failure = ShortcutException(payload, IllegalStateException("diagnostics", underlying))
            val radio = FakeRadio(contacts = listOf(alice), channels = listOf(ops),
                queueFailure = failure, advertFailure = failure)
            val presenter = FakePresenter()
            val androidText = AndroidShortcutText(context("fr"))
            var received: ShortcutError? = null
            val text = object : ShortcutText by androidText {
                override fun error(error: ShortcutError): String {
                    received = error
                    return androidText.error(error)
                }
            }
            ShortcutRequestExecutor(radio, FakeConfirmation(), text, presenter).run(request)
            assertSame(payload, received)
            assertEquals(listOf(context("fr").getString(L.errorMeshCoreFeatureDisabled)), presenter.messages)
            assertTrue(radio.queued.isEmpty())
            assertTrue(radio.adverts.isEmpty())
            assertTrue(presenter.opened.isEmpty())
        }
    }

    @Test
    fun cancellationStillPropagatesWithoutAnErrorPresentation() = runSuspend {
        val alice = contact()
        val cancelled = CancellationException("cancelled")
        val radio = FakeRadio(contacts = listOf(alice), queueFailure = cancelled)
        val presenter = FakePresenter()
        val executor = ShortcutRequestExecutor(radio, FakeConfirmation(), AndroidShortcutText(context("en")), presenter)
        assertSame(cancelled, assertFailsWith<CancellationException> {
            executor.run(ShortcutRequest.Send(TargetIdentity.contactId(alice), "hi"))
        })
        assertTrue(presenter.messages.isEmpty())
        assertTrue(radio.queued.isEmpty())
    }
}
