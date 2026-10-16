package one.meshcore.shared.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A full-screen nested destination (Material analog of a push). Use this instead of stuffing a form
 * into [androidx.compose.material3.ModalBottomSheet]. Presented as a window Dialog so a parent
 * Scaffold bar is covered and only this overlay's Up is shown. System Back dismisses via
 * [onDismiss].
 *
 * When [title] is set, a nested top bar with Up is drawn. Pass null when [content] already hosts
 * [AppNestedTopBar] so the chrome is not doubled. Content padding includes the system navigation
 * bar. [modifier] is for test tags; do not pass parent content padding — the Dialog fills the window.
 */
@Composable
fun FullScreenOverlay(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        DialogImeResizeEffect()
        val overlayInsets = WindowInsets.navigationBars.union(WindowInsets.ime)
        Surface(
            modifier = Modifier.fillMaxSize().then(modifier),
            color = MaterialTheme.colorScheme.surface,
        ) {
            if (title == null) {
                content(overlayInsets.asPaddingValues())
            } else {
                Scaffold(
                    contentWindowInsets = overlayInsets,
                    topBar = {
                        AppNestedTopBar(title = title, onBack = onDismiss)
                    },
                ) { innerPadding ->
                    content(innerPadding)
                }
            }
        }
    }
}
