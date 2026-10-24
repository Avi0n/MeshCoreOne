// AndroidOnly: WP-312 Required provider-specific legal links shared by every mounted MapLibre consumer.
package com.meshcoreone.android.core.maps

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
fun MapAttribution(attribution: List<MapProviderAttribution>, modifier: Modifier = Modifier) {
    val links = LocalUriHandler.current
    Surface(modifier) {
        FlowRow {
            attribution.forEach { provider ->
                TextButton(
                    onClick = { links.openUri(provider.legalUri) },
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) { Text(provider.label) }
            }
        }
    }
}
