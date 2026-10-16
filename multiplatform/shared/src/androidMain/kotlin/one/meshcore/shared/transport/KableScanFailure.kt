package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException

/**
 * Runs one Kable advertisement collect. Cancellation stops the scan. Any other failure, including
 * an [Error], is handed to [onFailure] so the picker can re-read permission and adapter state.
 * The caller then decides whether the sheet still wants another scan.
 */
internal suspend fun runKableScan(onFailure: () -> Unit, block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        onFailure()
    } catch (_: Error) {
        onFailure()
    }
}
