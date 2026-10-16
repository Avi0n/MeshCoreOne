package one.meshcore.shared.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.contact.ui.rememberContactServiceErrorText

internal const val MAP_LOADING_SCRIM_ALPHA = 0.1f

internal fun mapContactsLoadingScrim(scrim: Color) = scrim.copy(alpha = MAP_LOADING_SCRIM_ALPHA)

/**
 * First paint and the refresh control show loading chrome; advert-driven reloads stay quiet, matching
 * iOS `loadMapData(showsLoadingChrome:)`.
 */
internal fun mapContactsLoadShowsChrome(userInitiated: Boolean, hasCompletedInitialLoad: Boolean): Boolean =
    userInitiated || !hasCompletedInitialLoad

/**
 * Map tab: MapLibre host (Android) plus geolocated contact list, floating map controls, callout sheet,
 * and camera persistence.
 */
@Composable
internal fun MapScreen(inputs: MapScreenInputs, modifier: Modifier = Modifier) {
    val state = remember { MapScreenState() }
    val scope = rememberCoroutineScope()
    val contactErrorText = rememberContactServiceErrorText()
    MapScreenEffects(
        state = state,
        inputs = inputs,
        contactErrorText = contactErrorText,
    )
    MapScreenDialogs(state = state)
    MapScreenScaffold(
        state = state,
        scope = scope,
        modifier = modifier,
        inputs = inputs,
    )
    MapScreenCallouts(
        state = state,
        scope = scope,
        inputs = inputs,
        contactErrorText = contactErrorText,
        addInputs = MapDiscoveredAddInputs(
            contactService = inputs.contactService,
            isRadioConnected = inputs.isRadioConnected,
            nowEpochSeconds = inputs.nowEpochSeconds,
            maxContacts = inputs.maxContacts,
            contactErrorText = contactErrorText,
        ),
    )
}
