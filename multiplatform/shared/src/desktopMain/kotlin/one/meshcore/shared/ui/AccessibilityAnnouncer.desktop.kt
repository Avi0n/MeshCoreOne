package one.meshcore.shared.ui

import androidx.compose.runtime.Composable

// Compose for Desktop exposes no screen-reader bridge, so an announcement has nowhere to go.
private val NO_OP_ANNOUNCER = AccessibilityAnnouncer { }

@Composable
actual fun rememberAccessibilityAnnouncer(): AccessibilityAnnouncer = NO_OP_ANNOUNCER
