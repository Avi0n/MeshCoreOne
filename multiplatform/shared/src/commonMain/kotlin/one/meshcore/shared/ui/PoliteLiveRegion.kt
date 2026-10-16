package one.meshcore.shared.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/** Empty text whose description is announced politely when it changes. */
@Composable
fun PoliteLiveRegion(spoken: String) {
    Text(
        text = "",
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = spoken
        },
    )
}
