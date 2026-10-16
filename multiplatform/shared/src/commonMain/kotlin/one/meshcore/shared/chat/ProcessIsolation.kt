package one.meshcore.shared.chat

/**
 * Process-lifetime value with a test override. [withIsolatedStorage] pushes a fresh value and
 * restores the previous one, including a nested override.
 */
internal class ProcessIsolation<T>(private val empty: () -> T) {
    private val processStorage = empty()

    /** Test-only override. Production reads [processStorage]. */
    internal var isolatedStorage: T? = null

    val storage: T
        get() = isolatedStorage ?: processStorage

    fun withIsolatedStorage(block: () -> Unit) {
        val previous = isolatedStorage
        isolatedStorage = empty()
        try {
            block()
        } finally {
            isolatedStorage = previous
        }
    }

    suspend fun <R> withIsolatedStorageSuspending(block: suspend () -> R): R {
        val previous = isolatedStorage
        isolatedStorage = empty()
        try {
            return block()
        } finally {
            isolatedStorage = previous
        }
    }
}
