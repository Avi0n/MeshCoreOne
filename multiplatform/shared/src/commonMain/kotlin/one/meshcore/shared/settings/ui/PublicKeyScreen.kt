package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_public_key_base64_header
import one.meshcore.shared.resources.settings_public_key_copy
import one.meshcore.shared.resources.settings_public_key_footer
import one.meshcore.shared.resources.settings_public_key_header
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubSectionGap
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.plainTextClipEntry
import one.meshcore.shared.ui.preferenceHubScroll
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Dedicated public-key page: spaced hex, copy without spaces, and base64. */
@Composable
internal fun PublicKeyScreen(publicKey: ByteArray, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val hex = publicKey.toUppercaseHex()
    val spaced = hex.chunked(HEX_PAIR).joinToString(" ")
    Column(modifier.preferenceHubScroll()) {
        PreferenceHubSection(title = stringResource(Res.string.settings_public_key_header)) {
            Column(Modifier.padding(PreferenceHubCardPadding)) {
                Text(
                    text = spaced,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Button(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(plainTextClipEntry(hex))
                            haptics.success()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = AppIcons.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                    )
                    Text(stringResource(Res.string.settings_public_key_copy))
                }
            }
        }
        PreferenceHubFooter(stringResource(Res.string.settings_public_key_footer))
        PreferenceHubSection(
            modifier = Modifier.padding(top = PreferenceHubSectionGap),
            title = stringResource(Res.string.settings_public_key_base64_header),
        ) {
            Text(
                text = publicKey.toBase64Display(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(PreferenceHubCardPadding),
            )
        }
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun ByteArray.toBase64Display(): String = Base64.encode(this)

private const val HEX_PAIR = 2
