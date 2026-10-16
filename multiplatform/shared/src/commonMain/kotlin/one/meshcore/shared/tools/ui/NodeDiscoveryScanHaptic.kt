package one.meshcore.shared.tools.ui

internal enum class NodeDiscoveryScanHaptic {
    TAP,
    SUCCESS,
    WARNING,
}

internal fun nodeDiscoveryScanHaptic(
    wasScanning: Boolean,
    isScanning: Boolean,
    hasResults: Boolean,
): NodeDiscoveryScanHaptic? = when {
    !wasScanning && isScanning -> NodeDiscoveryScanHaptic.TAP
    wasScanning && !isScanning && hasResults -> NodeDiscoveryScanHaptic.SUCCESS
    wasScanning && !isScanning -> NodeDiscoveryScanHaptic.WARNING
    else -> null
}
