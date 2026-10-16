package one.meshcore.shared.chat.translation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runThrowableResult

/**
 * Per-conversation Translation session. Cache hits and Show original skip the translator. One
 * in-flight translation at a time. Never writes `MessageDto.text`.
 */
class MessageTranslationController(
    private val identify: suspend (String) -> Pair<String, Double>?,
    private val translate: suspend (text: String, sourceLang: String, targetLang: String) -> String,
    private val preferredLanguage: () -> String,
    private val offersEnabled: () -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val phases = mutableMapOf<Uuid, MessageTranslationPhase>()
    private val detectedLanguages = mutableMapOf<Uuid, DetectedLanguage>()
    private val cache = mutableMapOf<Uuid, MutableMap<String, String>>()
    private var generation = 0
    private val phaseDecisions = PhaseDecisions()
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun phase(id: Uuid): MessageTranslationPhase? = phases[id]

    fun chrome(id: Uuid): MessageTranslationPhase? = if (!offersEnabled()) {
        null
    } else {
        MessageTranslationChrome.resolved(
            detected = detectedLanguages[id],
            phase = phases[id],
            preferredLanguageCode = preferredLanguage(),
        )?.phase
    }

    /** Outgoing and from-self rows never show in-bubble Translation, matching iOS bake. */
    fun chrome(id: Uuid, isOutgoing: Boolean): MessageTranslationPhase? = if (isOutgoing) null else chrome(id)

    fun displayedText(id: Uuid, original: String): String {
        val showing = chrome(id) as? MessageTranslationPhase.Showing ?: return original
        return showing.translatedText
    }

    /**
     * Writes a missing detection key, including [DetectedLanguage.Undetermined], so a locale
     * change does not re-run.
     */
    suspend fun seedIfNeeded(id: Uuid, text: String) {
        if (!offersEnabled()) return
        if (mutex.withLock { detectedLanguages.containsKey(id) }) return
        // Language ID must not abort opening a thread that has messages.
        val hypothesis = runThrowableResult({ null }) {
            identify(MessageLanguageDetector.detectionSample(text))
        }
        mutex.withLock {
            if (offersEnabled() && !detectedLanguages.containsKey(id)) {
                detectedLanguages[id] = MessageLanguageDetector.dominantLanguage(text, hypothesis)
                phaseDecisions.bump()
            }
        }
    }

    suspend fun performAction(id: Uuid, text: String): TranslationPerformResult {
        seedIfNeeded(id, text)
        val target = MessageLanguageDetector.collapsedLanguageCode(preferredLanguage())
        val start = mutex.withLock { phaseDecisions.begin(id, target) }
        if (start !is ActionStart.Translate) return TranslationPerformResult.Finished
        return translatedAction(id, text, target, start)
    }

    private suspend fun translatedAction(
        id: Uuid,
        text: String,
        target: String,
        start: ActionStart.Translate,
    ): TranslationPerformResult = try {
        val translated = translate(text, start.source, target)
        mutex.withLock {
            if (start.generation == generation) {
                val perTarget = cache.getOrPut(id) { mutableMapOf() }
                perTarget[target] = translated
                phases[id] = MessageTranslationPhase.Showing(translated, target)
                phaseDecisions.bump()
            }
        }
        TranslationPerformResult.Finished
    } catch (cancelled: CancellationException) {
        resetFailedAction(id, start.generation)
        throw cancelled
    } catch (_: Throwable) {
        // A translator fault, including an Error, still offers the system sheet.
        resetFailedAction(id, start.generation)
        TranslationPerformResult.PresentSystemOverlay(text)
    }

    private sealed interface ActionStart {
        data object Finished : ActionStart
        data class Translate(val generation: Int, val source: String) : ActionStart
    }

    private suspend fun resetFailedAction(id: Uuid, startGeneration: Int) {
        mutex.withLock {
            if (startGeneration != generation) return
            phases[id] = MessageTranslationPhase.Offer
            phaseDecisions.bump()
        }
    }

    /** Drops in-flight work and showing chrome whose target is not [preferredLanguageCode]. */
    suspend fun invalidateTranslations(preferredLanguageCode: String) {
        mutex.withLock {
            generation += 1
            val preferred = MessageLanguageDetector.collapsedLanguageCode(preferredLanguageCode)
            for ((id, phase) in phases.toList()) {
                when (phase) {
                    MessageTranslationPhase.InProgress -> phases[id] = MessageTranslationPhase.Offer
                    is MessageTranslationPhase.Showing -> {
                        if (!MessageLanguageDetector.isSameLanguage(phase.targetLanguageCode, preferred)) {
                            phases[id] = MessageTranslationPhase.Offer
                        }
                    }
                    MessageTranslationPhase.Offer -> Unit
                }
            }
            phaseDecisions.bump()
        }
    }

    /**
     * Returns in-progress rows to the Translation offer. Leaves `.showing` and the translation cache
     * for a surviving conversation, matching iOS `cancelPendingTranslation`.
     */
    suspend fun cancelPendingTranslation() {
        mutex.withLock {
            generation += 1
            for ((id, phase) in phases.toList()) {
                if (phase is MessageTranslationPhase.InProgress) {
                    phases[id] = MessageTranslationPhase.Offer
                }
            }
            phaseDecisions.bump()
        }
    }

    private inner class PhaseDecisions {
        fun begin(id: Uuid, target: String): ActionStart =
            offerOrBusy(id) ?: cachedShowing(id, target) ?: started(id, target)

        fun offerOrBusy(id: Uuid): ActionStart? = when (phases[id]) {
            is MessageTranslationPhase.Showing -> {
                phases[id] = MessageTranslationPhase.Offer
                bump()
                ActionStart.Finished
            }
            is MessageTranslationPhase.InProgress -> ActionStart.Finished
            else -> null
        }

        fun cachedShowing(id: Uuid, target: String): ActionStart? {
            val cached = cache[id]?.get(target) ?: return null
            resetInProgress()
            phases[id] = MessageTranslationPhase.Showing(cached, target)
            bump()
            return ActionStart.Finished
        }

        fun started(id: Uuid, target: String): ActionStart {
            val sourceLang = detectedLanguages[id]?.code ?: return ActionStart.Finished
            if (MessageLanguageDetector.isSameLanguage(sourceLang, target)) {
                phases[id] = MessageTranslationPhase.Offer
                bump()
                return ActionStart.Finished
            }
            resetInProgress()
            generation += 1
            phases[id] = MessageTranslationPhase.InProgress
            bump()
            return ActionStart.Translate(generation, sourceLang)
        }

        fun resetInProgress() {
            val ids = phases.filterValues { it is MessageTranslationPhase.InProgress }.keys
            if (ids.isEmpty()) return
            generation += 1
            for (id in ids) phases[id] = MessageTranslationPhase.Offer
        }

        fun bump() {
            _revision.value = _revision.value + 1
        }
    }
}
