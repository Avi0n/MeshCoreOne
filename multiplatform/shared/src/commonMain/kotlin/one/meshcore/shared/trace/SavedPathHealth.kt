package one.meshcore.shared.trace

import androidx.compose.runtime.Composable
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_saved_paths_health_degraded
import one.meshcore.shared.resources.contacts_saved_paths_health_healthy
import one.meshcore.shared.resources.contacts_saved_paths_health_label
import one.meshcore.shared.resources.contacts_saved_paths_health_poor
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** How reliable a saved path has been, read off its success rate. Mirrors the iOS `SavedPathRow` dot. */
enum class SavedPathHealth {
    HEALTHY,
    DEGRADED,
    POOR,
}

/** At or above this success rate a path is [SavedPathHealth.HEALTHY]. */
const val HEALTHY_SUCCESS_RATE: Int = 90

/** At or above this success rate a path is [SavedPathHealth.DEGRADED]; below it, [SavedPathHealth.POOR]. */
const val DEGRADED_SUCCESS_RATE: Int = 50

/** The health band a success rate falls in, using the iOS thresholds. */
fun savedPathHealth(successRate: Int): SavedPathHealth = when {
    successRate >= HEALTHY_SUCCESS_RATE -> SavedPathHealth.HEALTHY
    successRate >= DEGRADED_SUCCESS_RATE -> SavedPathHealth.DEGRADED
    else -> SavedPathHealth.POOR
}

/** The shipped word for a health band. */
fun SavedPathHealth.wordResource(): StringResource = when (this) {
    SavedPathHealth.HEALTHY -> Res.string.contacts_saved_paths_health_healthy
    SavedPathHealth.DEGRADED -> Res.string.contacts_saved_paths_health_degraded
    SavedPathHealth.POOR -> Res.string.contacts_saved_paths_health_poor
}

/**
 * What a screen reader says for a saved-path row: the health band in words plus the rate. The row shows
 * the rate as a bare percentage, which on its own does not say what it measures.
 */
@Composable
fun savedPathHealthLabel(path: SavedTracePathDto): String {
    val rate = path.successRate
    val word = stringResource(savedPathHealth(rate).wordResource())
    return formatTemplate(stringResource(Res.string.contacts_saved_paths_health_label), listOf(word, rate))
}
