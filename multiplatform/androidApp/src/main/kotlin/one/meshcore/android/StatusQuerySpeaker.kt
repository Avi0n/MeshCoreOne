package one.meshcore.android

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.concurrent.atomic.AtomicReference

/**
 * Speaks a cached radio-status line without opening the Activity. Process-scoped so a lock-screen
 * notification action can finish after [TextToSpeech] init. Mirrors iOS `StatusQueryIntent` speech.
 */
internal object StatusQuerySpeaker {
    private val engine = AtomicReference<TextToSpeech?>(null)

    fun speak(context: Context, text: String, onDone: () -> Unit) {
        val existing = engine.get()
        if (existing != null) {
            existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            onDone()
            return
        }
        val app = context.applicationContext
        val created = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                engine.get()?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            }
            onDone()
        }
        if (!engine.compareAndSet(null, created)) {
            created.shutdown()
            engine.get()?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            onDone()
        }
    }

    private const val UTTERANCE_ID = "status-query"
}
