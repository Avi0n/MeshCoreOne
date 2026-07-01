import SwiftUI

/// Time range for filtering history charts.
enum HistoryTimeRange: String, CaseIterable {
  case week, month, threeMonths, all

  static let `default`: HistoryTimeRange = .month

  var label: String {
    switch self {
    case .week: L10n.RemoteNodes.RemoteNodes.History.week
    case .month: L10n.RemoteNodes.RemoteNodes.History.month
    case .threeMonths: L10n.RemoteNodes.RemoteNodes.History.threeMonths
    case .all: L10n.RemoteNodes.RemoteNodes.History.all
    }
  }

  var startDate: Date? {
    switch self {
    case .week: Calendar.current.date(byAdding: .day, value: -7, to: .now)
    case .month: Calendar.current.date(byAdding: .month, value: -1, to: .now)
    case .threeMonths: Calendar.current.date(byAdding: .month, value: -3, to: .now)
    case .all: nil
    }
  }
}

/// Segmented picker for selecting a history time range, styled for use in a List section.
struct HistoryTimeRangePicker: View {
  @Binding var selection: HistoryTimeRange

  var body: some View {
    Section {
      Picker(L10n.RemoteNodes.RemoteNodes.History.timeRange, selection: $selection) {
        ForEach(HistoryTimeRange.allCases, id: \.self) { range in
          Text(range.label).tag(range)
        }
      }
      .pickerStyle(.segmented)
      .listRowBackground(Color.clear)
    }
  }
}
