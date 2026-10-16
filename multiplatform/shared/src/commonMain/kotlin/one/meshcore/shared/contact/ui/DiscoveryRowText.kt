package one.meshcore.shared.contact.ui

import androidx.compose.ui.graphics.vector.ImageVector
import one.meshcore.shared.tools.storedDiscoveryName
import one.meshcore.shared.tools.unknownDiscoveredNodeName
import one.meshcore.shared.ui.icons.AppIcons

internal fun discoveryHeadlineText(name: String, unknownLabel: String, publicKey: ByteArray): String =
    storedDiscoveryName(name) ?: unknownDiscoveredNodeName(unknownLabel, publicKey)

/** iOS discovery empty uses the antenna glyph; search empty uses the magnifying glass. */
internal fun discoveryEmptyIcon(isSearch: Boolean): ImageVector = if (isSearch) AppIcons.Search else AppIcons.CellTower
