package one.meshcore.shared.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import org.jetbrains.compose.resources.stringResource

/**
 * TalkBack hint when a radio-backed control is disabled because the radio is not Ready.
 * iOS `View.radioDisabled(for:)` accessibilityHint.
 */
fun Modifier.radioConnectionHint(isRadioReady: Boolean, hint: String): Modifier =
    if (isRadioReady) this else semantics { stateDescription = hint }

/** Loads the shipped "Requires radio connection" copy and applies [radioConnectionHint]. */
@Composable
fun Modifier.radioConnectionHint(isRadioReady: Boolean): Modifier = radioConnectionHint(
    isRadioReady,
    stringResource(Res.string.localizable_accessibility_requires_radio_connection),
)
