package one.meshcore.shared.settings.ui

import org.jetbrains.compose.resources.StringResource

/** The localized string a backup failure renders as: the resource plus its format arguments. */
internal data class BackupErrorCopy(val resource: StringResource, val args: List<Any> = emptyList())
