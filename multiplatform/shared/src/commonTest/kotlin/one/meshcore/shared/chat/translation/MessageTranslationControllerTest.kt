package one.meshcore.shared.chat.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class MessageTranslationControllerTest {
    private val french = "Bonjour tout le monde ici"

    @Test
    fun translateThenShowOriginalTogglesWithoutRewriting() = runTest {
        val controller = controller(identify = { "fr" to 0.95 }, translate = { text, _, _ -> "Hello $text" })
        val id = ChatFixtures.freshUuid()
        controller.performAction(id, french)
        val showing = controller.phase(id)
        assertIs<MessageTranslationPhase.Showing>(showing)
        assertEquals("Hello $french", controller.displayedText(id, french))
        assertEquals(TranslationPerformResult.Finished, controller.performAction(id, french))
        assertEquals(MessageTranslationPhase.Offer, controller.phase(id))
        assertEquals(french, controller.displayedText(id, french))
    }

    @Test
    fun cacheHitSkipsTranslator() = runTest {
        var calls = 0
        val controller = controller(
            identify = { "fr" to 0.95 },
            translate = { text, _, _ ->
                calls += 1
                "Hello $text"
            },
        )
        val id = ChatFixtures.freshUuid()
        controller.performAction(id, french)
        controller.performAction(id, french)
        controller.performAction(id, french)
        assertEquals(1, calls)
        assertIs<MessageTranslationPhase.Showing>(controller.phase(id))
    }

    @Test
    fun sameLanguageStaysOffer() = runTest {
        val controller = controller(
            identify = { "en" to 0.99 },
            translate = { _, _, _ -> error("should not translate") },
            preferred = "en-US",
        )
        val id = ChatFixtures.freshUuid()
        controller.performAction(id, "Hello there friend")
        assertEquals(MessageTranslationPhase.Offer, controller.phase(id))
        assertNull((controller.phase(id) as? MessageTranslationPhase.Showing))
    }

    @Test
    fun identifierFailureDoesNotCrashAndLeavesNoChrome() = runTest {
        val controller = controller(
            identify = { error("mlkit factory") },
            translate = { _, _, _ -> error("no") },
        )
        val id = ChatFixtures.freshUuid()
        controller.seedIfNeeded(id, french)
        assertNull(controller.chrome(id))
    }

    @Test
    fun seedOffersChromeForForeignText() = runTest {
        val controller = controller(identify = { "fr" to 0.95 }, translate = { _, _, _ -> error("no") })
        val id = ChatFixtures.freshUuid()
        assertNull(controller.chrome(id))
        controller.seedIfNeeded(id, french)
        assertEquals(MessageTranslationPhase.Offer, controller.chrome(id))
        assertNull(controller.phase(id))
    }

    @Test
    fun disabledOffersSkipDetectionAndHideExistingChromeUntilReenabled() = runTest {
        var enabled = false
        var detections = 0
        val controller = MessageTranslationController(
            identify = {
                detections += 1
                "fr" to 0.95
            },
            translate = { _, _, _ -> error("no") },
            preferredLanguage = { "en" },
            offersEnabled = { enabled },
        )
        val id = ChatFixtures.freshUuid()
        controller.seedIfNeeded(id, french)
        assertEquals(0, detections)
        assertNull(controller.chrome(id))
        enabled = true
        controller.seedIfNeeded(id, french)
        assertEquals(1, detections)
        assertEquals(MessageTranslationPhase.Offer, controller.chrome(id))
        enabled = false
        assertNull(controller.chrome(id))
    }

    @Test
    fun outgoingRowsHaveNoChromeEvenAfterDetection() = runTest {
        val controller = controller(identify = { "fr" to 0.95 }, translate = { _, _, _ -> error("no") })
        val id = ChatFixtures.freshUuid()
        controller.seedIfNeeded(id, french)
        assertEquals(MessageTranslationPhase.Offer, controller.chrome(id))
        assertNull(controller.chrome(id, isOutgoing = true))
    }

    @Test
    fun seedDoesNotOfferChromeForSameLanguage() = runTest {
        val controller = controller(
            identify = { "en" to 0.99 },
            translate = { _, _, _ -> error("no") },
            preferred = "en-US",
        )
        val id = ChatFixtures.freshUuid()
        controller.seedIfNeeded(id, "Hello there friend")
        assertNull(controller.chrome(id))
    }

    @Test
    fun seedIdentifiesTheMentionStrippedSample() = runTest {
        var identified: String? = null
        val controller = controller(
            identify = { text ->
                identified = text
                "fr" to 0.99
            },
            translate = { _, _, _ -> error("no") },
        )
        val id = ChatFixtures.freshUuid()
        controller.seedIfNeeded(id, "@[1wNodeTest]: no problems!")
        assertEquals(": no problems!", identified)
        assertEquals(MessageTranslationPhase.Offer, controller.chrome(id))
    }

    @Test
    fun invalidateDropsShowingForADifferentTarget() = runTest {
        val controller = controller(identify = { "fr" to 0.95 }, translate = { text, _, _ -> "Hello $text" })
        val id = ChatFixtures.freshUuid()
        controller.performAction(id, french)
        assertIs<MessageTranslationPhase.Showing>(controller.phase(id))
        controller.invalidateTranslations("de")
        assertEquals(MessageTranslationPhase.Offer, controller.phase(id))
        assertEquals(MessageTranslationPhase.Offer, controller.chrome(id))
    }

    @Test
    fun translatorFailureReturnsOverlayAndOffer() = runTest {
        val controller = controller(
            identify = { "fr" to 0.95 },
            translate = { _, _, _ -> error("download") },
        )
        val id = ChatFixtures.freshUuid()
        val result = controller.performAction(id, french)
        assertEquals(MessageTranslationPhase.Offer, controller.phase(id))
        assertEquals(TranslationPerformResult.PresentSystemOverlay(french), result)
    }

    @Test
    fun translatorCancellationResetsOfferAndRethrows() = runTest {
        val cancelled = CancellationException("cancelled")
        val controller = controller(
            identify = { "fr" to 0.95 },
            translate = { _, _, _ -> throw cancelled },
        )
        val id = ChatFixtures.freshUuid()
        val thrown = assertFailsWith<CancellationException> {
            controller.performAction(id, french)
        }
        assertSame(cancelled, thrown)
        assertEquals(MessageTranslationPhase.Offer, controller.phase(id))
    }

    @Test
    fun translatorErrorReturnsOverlayAndOffer() = runTest {
        val controller = controller(
            identify = { "fr" to 0.95 },
            translate = { _, _, _ -> throw TranslationProbeError() },
        )
        val id = ChatFixtures.freshUuid()
        val result = controller.performAction(id, french)
        assertEquals(MessageTranslationPhase.Offer, controller.phase(id))
        assertEquals(TranslationPerformResult.PresentSystemOverlay(french), result)
    }

    @Test
    fun presentOverlayIfNeededInvokesOnlyForOverlay() {
        var presented: String? = null
        TranslationPerformResult.Finished.presentOverlayIfNeeded { presented = it }
        assertNull(presented)
        TranslationPerformResult.PresentSystemOverlay("hola").presentOverlayIfNeeded { presented = it }
        assertEquals("hola", presented)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelPendingTranslationResetsInProgressAndKeepsShowing() = runTest {
        val gate = CompletableDeferred<Unit>()
        var translateCalls = 0
        val controller = controller(
            identify = { "fr" to 0.95 },
            translate = { text, _, _ ->
                translateCalls += 1
                if (translateCalls > 1) gate.await()
                "Hello $text"
            },
        )
        val shown = ChatFixtures.freshUuid()
        val pending = ChatFixtures.freshUuid()
        controller.performAction(shown, french)
        assertIs<MessageTranslationPhase.Showing>(controller.phase(shown))
        val job = launch {
            controller.performAction(pending, french)
        }
        runCurrent()
        assertEquals(MessageTranslationPhase.InProgress, controller.phase(pending))
        controller.cancelPendingTranslation()
        gate.complete(Unit)
        job.join()
        assertIs<MessageTranslationPhase.Showing>(controller.phase(shown))
        assertEquals(MessageTranslationPhase.Offer, controller.phase(pending))
    }

    private fun controller(
        identify: suspend (String) -> Pair<String, Double>?,
        translate: suspend (String, String, String) -> String,
        preferred: String = "en",
    ) = MessageTranslationController(
        identify = identify,
        translate = translate,
        preferredLanguage = { preferred },
    )
}

private class TranslationProbeError : Error("probe")
