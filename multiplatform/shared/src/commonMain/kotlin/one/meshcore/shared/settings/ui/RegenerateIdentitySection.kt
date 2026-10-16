package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_key_generation_max_attempts_exceeded
import one.meshcore.shared.resources.localizable_error_key_generation_random_generation_failed
import one.meshcore.shared.resources.settings_private_key_header
import one.meshcore.shared.resources.settings_public_key_copy
import one.meshcore.shared.resources.settings_public_key_header
import one.meshcore.shared.resources.settings_regenerate_identity_generate
import one.meshcore.shared.resources.settings_regenerate_identity_generate_another
import one.meshcore.shared.resources.settings_regenerate_identity_generating
import one.meshcore.shared.resources.settings_regenerate_identity_importing
import one.meshcore.shared.resources.settings_regenerate_identity_prefix_error_invalid_length
import one.meshcore.shared.resources.settings_regenerate_identity_prefix_error_reserved
import one.meshcore.shared.resources.settings_regenerate_identity_prefix_footer
import one.meshcore.shared.resources.settings_regenerate_identity_prefix_label
import one.meshcore.shared.resources.settings_regenerate_identity_prefix_placeholder
import one.meshcore.shared.resources.settings_regenerate_identity_replace
import one.meshcore.shared.resources.settings_regenerate_identity_sheet_explanation
import one.meshcore.shared.resources.settings_regenerate_identity_sheet_title
import one.meshcore.shared.settings.KeyGenerationService
import one.meshcore.shared.settings.VanityPrefix
import one.meshcore.shared.ui.AppHaptics
import one.meshcore.shared.ui.AppOutlinedTextField
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun RegenerateIdentitySection(
    busy: Boolean,
    rejectedMessage: String,
    featureDisabledMessage: String,
    sessionText: (Throwable) -> String,
    onBusyChange: (Boolean) -> Unit,
    onError: (String?) -> Unit,
    onImportPrivateKey: suspend (ByteArray) -> Unit,
    onSuccess: () -> Unit = {},
    haptics: AppHaptics,
    scope: CoroutineScope,
) {
    var vanityPrefix by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var isImporting by remember { mutableStateOf(false) }
    var generatedIdentity by remember { mutableStateOf<KeyGenerationService.GeneratedIdentity?>(null) }
    var showReplaceConfirm by remember { mutableStateOf(false) }
    var prefixError by remember { mutableStateOf<String?>(null) }
    val lengthError = stringResource(Res.string.settings_regenerate_identity_prefix_error_invalid_length)
    val reservedError = stringResource(Res.string.settings_regenerate_identity_prefix_error_reserved)
    val maxAttemptsError =
        stringResource(Res.string.localizable_error_key_generation_max_attempts_exceeded)
    val randomFailedError =
        stringResource(Res.string.localizable_error_key_generation_random_generation_failed)
    val generatingLabel = stringResource(Res.string.settings_regenerate_identity_generating)
    val importingLabel = stringResource(Res.string.settings_regenerate_identity_importing)

    PreferenceHubSection(title = stringResource(Res.string.settings_regenerate_identity_sheet_title)) {
        Column(Modifier.padding(PreferenceHubCardPadding)) {
            Text(
                text = stringResource(Res.string.settings_regenerate_identity_sheet_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            AppOutlinedTextField(
                value = vanityPrefix,
                onValueChange = {
                    vanityPrefix = VanityPrefix.sanitize(it)
                    prefixError = null
                    onError(null)
                },
                label = { Text(stringResource(Res.string.settings_regenerate_identity_prefix_label)) },
                placeholder = { Text(stringResource(Res.string.settings_regenerate_identity_prefix_placeholder)) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                singleLine = true,
                enabled = !busy && !isGenerating,
                isError = prefixError != null,
                supportingText = {
                    Text(stringResource(Res.string.settings_regenerate_identity_prefix_footer))
                },
            )
            prefixError?.let { message ->
                NodeConfigErrorLabel(message, icon = AppIcons.Warning)
            }
            OutlinedButton(
                onClick = {
                    launchIdentityGeneration(
                        scope,
                        IdentityGeneration(
                            prefix = vanityPrefix.trim(),
                            lengthError = lengthError,
                            reservedError = reservedError,
                            maxAttemptsError = maxAttemptsError,
                            randomFailedError = randomFailedError,
                            onPrefixError = { prefixError = it },
                            onError = onError,
                            onBusyChange = onBusyChange,
                            onGeneratingChange = { isGenerating = it },
                            onGenerated = { generatedIdentity = it },
                            generate = { hexPrefix ->
                                KeyGenerationService.generateIdentity(hexPrefix = hexPrefix)
                            },
                        ),
                    )
                },
                enabled = !busy && !isGenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isGenerating) {
                        generatingLabel
                    } else if (generatedIdentity != null) {
                        stringResource(Res.string.settings_regenerate_identity_generate_another)
                    } else {
                        stringResource(Res.string.settings_regenerate_identity_generate)
                    },
                )
            }

            generatedIdentity?.let { identity ->
                GeneratedIdentityPreview(identity)
                OutlinedButton(
                    onClick = { showReplaceConfirm = true },
                    enabled = !busy && !isGenerating,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        if (isImporting) {
                            importingLabel
                        } else {
                            stringResource(Res.string.settings_regenerate_identity_replace)
                        },
                    )
                }
            }
        }
    }

    if (showReplaceConfirm) {
        val identity = generatedIdentity
        ReplaceIdentityConfirmDialog(
            onDismiss = { showReplaceConfirm = false },
            onConfirm = {
                showReplaceConfirm = false
                launchIdentityReplace(
                    scope,
                    IdentityReplace(
                        identity = identity,
                        featureDisabledMessage = featureDisabledMessage,
                        rejectedMessage = rejectedMessage,
                        sessionText = sessionText,
                        onError = onError,
                        onBusyChange = onBusyChange,
                        onImportingChange = { isImporting = it },
                        onImportPrivateKey = onImportPrivateKey,
                        onCleared = {
                            generatedIdentity = null
                            vanityPrefix = ""
                        },
                        onHapticSuccess = { haptics.success() },
                        onSuccess = onSuccess,
                    ),
                )
            },
        )
    }
}

@Composable
internal fun GeneratedIdentityPreview(identity: KeyGenerationService.GeneratedIdentity) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val publicHex = identity.publicKey.toUppercaseHex()
    val privateHex = identity.expandedPrivateKey.toUppercaseHex()
    val copyHex: (String) -> Unit = { hex ->
        scope.launch {
            clipboard.setClipEntry(plainTextClipEntry(hex))
            haptics.success()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ListItem(
            headlineContent = { Text(stringResource(Res.string.settings_public_key_header)) },
            supportingContent = {
                Text(
                    text = publicHex,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.semantics {
                        contentDescription = generatedPublicKeyAccessibilityLabel(identity.publicKey)
                    },
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { copyHex(publicHex) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.settings_public_key_copy))
        }
        ListItem(
            headlineContent = { Text(stringResource(Res.string.settings_private_key_header)) },
            supportingContent = {
                Text(
                    text = privateHex,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { copyHex(privateHex) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.settings_public_key_copy))
        }
    }
}
