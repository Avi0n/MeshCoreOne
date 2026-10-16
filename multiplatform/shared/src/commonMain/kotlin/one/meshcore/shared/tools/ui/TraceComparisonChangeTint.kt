package one.meshcore.shared.tools.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import one.meshcore.shared.trace.RttTrend
import one.meshcore.shared.ui.SignalChromeRole

/** iOS `ComparisonRowView`: increased is `.red`, decreased is `.green`. */
internal enum class TraceComparisonChangeTint { RED, GREEN }

internal fun TraceComparisonChangeTint.chromeRole(): SignalChromeRole = when (this) {
    TraceComparisonChangeTint.RED -> SignalChromeRole.ERROR
    TraceComparisonChangeTint.GREEN -> SignalChromeRole.PRIMARY
}

internal fun traceComparisonChangeTint(trend: RttTrend): TraceComparisonChangeTint? = when (trend) {
    RttTrend.INCREASED -> TraceComparisonChangeTint.RED
    RttTrend.DECREASED -> TraceComparisonChangeTint.GREEN
    RttTrend.UNCHANGED -> null
}
