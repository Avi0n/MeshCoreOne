import Foundation

// MARK: - Duration

extension MapDurationUnit {
  var localizedName: String {
    switch self {
    case .minutes: L10n.Map.Map.Filters.LastHeard.Unit.minutes
    case .hours: L10n.Map.Map.Filters.LastHeard.Unit.hours
    case .days: L10n.Map.Map.Filters.LastHeard.Unit.days
    }
  }

  fileprivate var calendarUnit: NSCalendar.Unit {
    switch self {
    case .minutes: .minute
    case .hours: .hour
    case .days: .day
    }
  }
}

extension MapDuration {
  /// Compact, localized form in the entered unit (for example "15m", "2h", "3d").
  var abbreviated: String {
    let formatter = DateComponentsFormatter()
    formatter.unitsStyle = .abbreviated
    formatter.allowedUnits = unit.calendarUnit
    formatter.maximumUnitCount = 1
    return formatter.string(from: seconds) ?? "\(value)"
  }
}

// MARK: - Last Heard

extension MapLastHeardRange {
  var chipText: String {
    switch (minAge, maxAge) {
    case let (min?, max?): L10n.Map.Map.Filters.LastHeard.Chip.between(min.abbreviated, max.abbreviated)
    case let (min?, nil): L10n.Map.Map.Filters.LastHeard.Chip.olderThan(min.abbreviated)
    case let (nil, max?): L10n.Map.Map.Filters.LastHeard.Chip.within(max.abbreviated)
    case (nil, nil): L10n.Map.Map.Filters.LastHeard.anyTime
    }
  }

  var summaryText: String {
    switch (minAge, maxAge) {
    case let (min?, max?): L10n.Map.Map.Filters.LastHeard.Summary.between(min.abbreviated, max.abbreviated)
    case let (min?, nil): L10n.Map.Map.Filters.LastHeard.Summary.olderThan(min.abbreviated)
    case let (nil, max?): L10n.Map.Map.Filters.LastHeard.Summary.within(max.abbreviated)
    case (nil, nil): L10n.Map.Map.Filters.LastHeard.Summary.any
    }
  }

  /// Short label for a preset capsule.
  var presetLabel: String {
    guard let maxAge, minAge == nil else { return L10n.Map.Map.Filters.LastHeard.anyTime }
    return maxAge.abbreviated
  }
}

// MARK: - Hops

extension MapHopRange {
  var chipText: String {
    switch (min, max) {
    case (0, 0): L10n.Map.Map.Filters.Hops.Chip.direct
    case let (low, high?) where low == high: L10n.Map.Map.Filters.Hops.Chip.exactly(low)
    case let (0, high?): L10n.Map.Map.Filters.Hops.Chip.atMost(high)
    case let (low, high?): L10n.Map.Map.Filters.Hops.Chip.between(low, high)
    case (0, nil): L10n.Map.Map.Filters.Hops.any
    case let (low, nil): L10n.Map.Map.Filters.Hops.Chip.atLeast(low)
    }
  }

  var summaryText: String {
    switch (min, max) {
    case (0, 0): L10n.Map.Map.Filters.Hops.Summary.direct
    case let (low, high?) where low == high: L10n.Map.Map.Filters.Hops.Summary.exactly(low)
    case let (0, high?): L10n.Map.Map.Filters.Hops.Summary.atMost(high)
    case let (low, high?): L10n.Map.Map.Filters.Hops.Summary.between(low, high)
    case (0, nil): L10n.Map.Map.Filters.Hops.Summary.any
    case let (low, nil): L10n.Map.Map.Filters.Hops.Summary.atLeast(low)
    }
  }

  /// Short label for a preset capsule.
  var presetLabel: String {
    switch (min, max) {
    case (0, nil): L10n.Map.Map.Filters.Hops.any
    case (0, 0): L10n.Map.Map.Filters.Hops.direct
    case let (0, high?): L10n.Map.Map.Filters.Hops.upTo(high)
    default: chipText
    }
  }
}

// MARK: - Filter State

extension MapFilterState {
  func chipText(for dimension: MapAdvancedFilterDimension) -> String {
    switch dimension {
    case .lastHeard: lastHeard.chipText
    case .hops: hops.chipText
    }
  }
}
