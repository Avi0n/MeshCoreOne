package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_duration_ms
import org.jetbrains.compose.resources.stringResource

internal fun durationMsVisibleText(ms: Int, template: String): String = formatTemplate(template, listOf(ms))

/** Localized `N ms` for ping and Trace Path round-trip values. */
@Composable
fun durationMsLabel(ms: Int): String = durationMsVisibleText(ms, stringResource(Res.string.android_duration_ms))
