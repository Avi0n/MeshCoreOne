package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A hidden retained form keeps its success indication until a mount can display it. */
internal class SettingsApplyFeedback(private val retained: Boolean) : AbstractCoroutineContextElement(Key) {
    internal enum class Kind {
        IDENTITY,
        CONTACT_INFO,
        BEHAVIOR,
        ROOM_ACCESS,
        PASSWORD,
        REGIONS,
        RADIO,
        REBOOT,
        ADVERT,
        TIME_SYNC,
    }
    private val mounts = mutableSetOf<Long>()
    private val visible = MutableStateFlow(!retained)
    private val generations = mutableMapOf<Kind, Long>()
    private var nextMount = 0L

    fun shown(): Long = (++nextMount).also {
        mounts.add(it)
        visible.value = true
    }

    fun hidden(token: Long) {
        mounts.remove(token)
        if (retained) visible.value = mounts.isNotEmpty()
    }

    fun begin(kind: Kind): Long = ((generations[kind] ?: 0L) + 1L).also { generations[kind] = it }
    fun isCurrent(kind: Kind, generation: Long): Boolean = generations[kind] == generation

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun waitUntilDisplayed() {
        visible.transformLatest { showing ->
            if (showing) {
                delay(APPLY_SUCCESS_FLASH_MS)
                emit(Unit)
            }
        }.first()
    }

    companion object Key : CoroutineContext.Key<SettingsApplyFeedback>
}
