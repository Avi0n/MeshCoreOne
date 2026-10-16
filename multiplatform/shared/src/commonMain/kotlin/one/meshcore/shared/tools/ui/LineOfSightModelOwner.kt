package one.meshcore.shared.tools.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.map.ElevationService
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.tools.LineOfSightViewModel
import one.meshcore.shared.tools.configure
import one.meshcore.shared.tools.loadRepeaters
import one.meshcore.shared.tools.seedDeviceFrequency

/** Retains LOS edits while following replacement connection providers and their repeater rows. */
@Composable
internal fun rememberLineOfSightViewModel(
    elevationService: ElevationService?,
    contactService: (() -> ContactService?)?,
    radioId: () -> Uuid?,
    deviceFrequencyKHz: Long?,
    workspace: LineOfSightViewModel? = null,
): LineOfSightViewModel {
    val scope = rememberCoroutineScope()
    val currentContactService by rememberUpdatedState(contactService)
    val currentRadioId by rememberUpdatedState(radioId)
    val contactServiceIdentity = contactService?.invoke()
    val radioIdentity = radioId()
    val viewModel = workspace ?: remember(elevationService) {
        LineOfSightViewModel(
            scope = scope,
            elevationService = elevationService,
            fetchContacts = { id -> currentContactService?.invoke()?.getContacts(id) ?: emptyList() },
        )
    }
    DisposableEffect(viewModel, workspace) {
        onDispose { if (workspace == null) viewModel.close() }
    }
    LaunchedEffect(viewModel, contactServiceIdentity, radioIdentity, deviceFrequencyKHz) {
        if (workspace == null) {
            viewModel.configure(radioId = { currentRadioId() }, deviceFrequencyKHz = deviceFrequencyKHz)
        } else {
            viewModel.seedDeviceFrequency(deviceFrequencyKHz)
        }
        viewModel.loadRepeaters()
    }
    return viewModel
}
