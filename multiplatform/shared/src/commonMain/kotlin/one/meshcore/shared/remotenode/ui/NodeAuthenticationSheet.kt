package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_authentication
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_connect
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_flood_retry_status
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_join_room
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_management
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_name
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_node_details
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_password_too_long_repeaters
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_password_too_long_rooms
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_remember_password
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_seconds_remaining
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_seconds_remaining_announcement
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_type
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_type_repeater
import one.meshcore.shared.resources.remotenodes_remote_nodes_auth_type_room
import one.meshcore.shared.resources.remotenodes_remote_nodes_status_request_timed_out
import one.meshcore.shared.settings.ui.SettingsToggleRow
import one.meshcore.shared.ui.AppNestedTopBar
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PoliteLiveRegion
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.PreferenceHubValueRow
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.preferenceHubFormScroll
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * Node authentication, ported from the iOS `NodeAuthenticationSheet`. Password entry (capped at
 * [NodeAuthViewModel.MAX_PASSWORD_LENGTH]) with a remember toggle, flood-routing toggle, and resolved
 * path preview, over [NodeAuthViewModel]'s connect flow. On success the screen dismisses and hands the
 * session to [onSuccess]. Nested destination Up matches Channel Info, not iOS sheet Cancel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeAuthenticationSheet(
    viewModel: NodeAuthViewModel,
    contact: ContactDto,
    role: RemoteNodeRole,
    onSuccess: (RemoteNodeSessionDto) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    hideNodeDetails: Boolean = false,
    customTitle: String? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()

    LaunchedEffect(viewModel, contact.id) {
        viewModel.loadSavedPassword()
        viewModel.loadPath()
    }

    // The iOS sheet fires an error sensory-feedback on each new error; mirror it with the failure haptic.
    LaunchedEffect(state.errorMessage) {
        if (state.errorMessage != null) haptics.failure()
    }

    val title = customTitle ?: stringResource(
        if (role == RemoteNodeRole.ROOM_SERVER) {
            Res.string.remotenodes_remote_nodes_auth_join_room
        } else {
            Res.string.remotenodes_remote_nodes_auth_management
        },
    )

    val dismissAuth = {
        viewModel.cancel()
        onDismiss()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            AppNestedTopBar(
                title = title,
                onBack = dismissAuth,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(SECTION_PADDING),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
            Column(
                modifier = preferenceHubFormScroll(),
                verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
            ) {
                if (!hideNodeDetails) {
                    NodeDetailsSection(name = contact.displayName, role = role)
                }
                AuthenticationSection(state = state, role = role, viewModel = viewModel)
                PathSection(
                    state = state,
                    contact = contact,
                    hasStoredPath = !contact.isFloodRouted,
                    viewModel = viewModel,
                )
            }
            ConnectButton(
                role = role,
                isAuthenticating = state.isAuthenticating,
                onConnect = {
                    viewModel.authenticate { session ->
                        onDismiss()
                        onSuccess(session)
                    }
                },
            )
        }
    }
}

@Composable
private fun NodeDetailsSection(name: String, role: RemoteNodeRole) {
    PreferenceHubSection(
        title = stringResource(Res.string.remotenodes_remote_nodes_auth_node_details),
        applyHorizontalPadding = false,
    ) {
        PreferenceHubValueRow(stringResource(Res.string.remotenodes_remote_nodes_auth_name), name)
        PreferenceHubValueRow(
            stringResource(Res.string.remotenodes_remote_nodes_auth_type),
            stringResource(
                if (role == RemoteNodeRole.ROOM_SERVER) {
                    Res.string.remotenodes_remote_nodes_auth_type_room
                } else {
                    Res.string.remotenodes_remote_nodes_auth_type_repeater
                },
            ),
        )
    }
}

@Composable
private fun AuthenticationSection(state: NodeAuthState, role: RemoteNodeRole, viewModel: NodeAuthViewModel) {
    PreferenceHubSection(
        title = stringResource(Res.string.remotenodes_remote_nodes_auth_authentication),
        applyHorizontalPadding = false,
    ) {
        AppOutlinedTextField(
            value = state.password,
            onValueChange = viewModel::setPassword,
            label = { Text(stringResource(Res.string.remotenodes_remote_nodes_auth_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PreferenceHubCardPadding),
        )
        ToggleRow(
            label = stringResource(Res.string.remotenodes_remote_nodes_auth_remember_password),
            checked = state.rememberPassword,
            onCheckedChange = viewModel::setRememberPassword,
        )
        AuthenticationFooter(state = state, role = role)
    }
}

@Composable
private fun AuthenticationFooter(state: NodeAuthState, role: RemoteNodeRole) {
    val error = state.errorMessage
    val remaining = state.secondsRemaining
    when {
        error != null -> {
            val errorText = when (error) {
                NodeAuthError.Timeout -> stringResource(Res.string.remotenodes_remote_nodes_status_request_timed_out)
                is NodeAuthError.Message -> error.text
            }
            val spoken = authErrorSpokenLabel(
                errorText,
                stringResource(authErrorSpokenResource()),
            )
            ListItem(
                headlineContent = {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = NODE_AUTH_ERROR_ICON,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = spoken },
            )
        }
        state.password.length > NodeAuthViewModel.MAX_PASSWORD_LENGTH -> PreferenceHubStatusRow(
            formattedStringResource(
                if (role == RemoteNodeRole.REPEATER) {
                    Res.string.remotenodes_remote_nodes_auth_password_too_long_repeaters
                } else {
                    Res.string.remotenodes_remote_nodes_auth_password_too_long_rooms
                },
                NodeAuthViewModel.MAX_PASSWORD_LENGTH,
            ),
        )
        state.isRetryingViaFlood -> {
            PreferenceHubStatusRow(
                stringResource(Res.string.remotenodes_remote_nodes_auth_flood_retry_status),
            )
            if (remaining != null && remaining > 0) {
                SecondsRemainingText(remaining)
            }
        }
        remaining != null && remaining > 0 -> SecondsRemainingText(remaining)
    }
    CountdownAnnouncement(state.secondsRemainingAnnouncement)
}

@Composable
private fun SecondsRemainingText(remaining: Int) {
    PreferenceHubStatusRow(
        formattedStringResource(Res.string.remotenodes_remote_nodes_auth_seconds_remaining, remaining),
    )
}

/**
 * A visually-empty live region that reads the countdown aloud only when [announcement] changes, so TalkBack
 * speaks the coarse thresholds and the final seconds without repeating every tick (the iOS announcement
 * mirror). The seconds live in the semantics description, not visible text.
 */
@Composable
private fun CountdownAnnouncement(announcement: Int?) {
    if (announcement == null) return
    val spoken = formattedStringResource(
        Res.string.remotenodes_remote_nodes_auth_seconds_remaining_announcement,
        announcement,
    )
    PoliteLiveRegion(spoken)
}

@Composable
private fun ConnectButton(role: RemoteNodeRole, isAuthenticating: Boolean, onConnect: () -> Unit) {
    Button(
        onClick = onConnect,
        enabled = !isAuthenticating,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isAuthenticating) {
            FilledButtonProgress()
        }
        Text(
            stringResource(
                if (role == RemoteNodeRole.ROOM_SERVER) {
                    Res.string.remotenodes_remote_nodes_auth_join_room
                } else {
                    Res.string.remotenodes_remote_nodes_auth_connect
                },
            ),
        )
    }
}

@Composable
internal fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    disabledHint: String? = null,
) {
    SettingsToggleRow(
        label = label,
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        // The iOS accessibilityHint when the toggle is unavailable for a no-route contact; state description
        // reads after the label and toggle state without clobbering either.
        modifier = if (disabledHint != null) {
            Modifier.semantics { stateDescription = disabledHint }
        } else {
            Modifier
        },
    )
}

private val SECTION_PADDING = 16.dp
private val SECTION_SPACING = 12.dp

/** iOS node-auth footer `exclamationmark.circle.fill` (system orange). */
internal val NODE_AUTH_ERROR_ICON get() = AppIcons.Error
