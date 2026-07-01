import Foundation
import MC1Services

/// Push destinations inside the trace-results stack, registered by `TraceResultsSheet` so the
/// comparison row can drill into a saved path's history. Value-based so each push rebuilds the
/// destination instead of reusing stale `@State` from a prior visit.
enum TracePathRoute: Hashable {
  case savedPathDetail(SavedTracePathDTO)

  /// Run drill-down push destination. `SavedPathDetailView` registers and pushes this itself
  /// so the destination travels with the view regardless of which stack hosts it.
  struct RunDetail: Hashable {
    let run: TracePathRunDTO
  }
}
