import SwiftUI

/// Displays a relative timestamp using Apple's localized relative date formatting
struct RelativeTimestampText: View {
  let date: Date

  /// Wire-format unix seconds (contact/message timestamps from the radio).
  init(timestamp: UInt32) {
    self.date = Date(timeIntervalSince1970: TimeInterval(timestamp))
  }

  /// Receiver-clock or other already-resolved `Date` values.
  init(date: Date) {
    self.date = date
  }

  private static let relativeFormatter: RelativeDateTimeFormatter = {
    let formatter = RelativeDateTimeFormatter()
    formatter.unitsStyle = .abbreviated
    return formatter
  }()

  private static let weekThreshold: TimeInterval = 604_800
  private static let nowThreshold: TimeInterval = 60

  var body: some View {
    TimelineView(.everyMinute) { context in
      Text(Self.format(date: date, relativeTo: context.date))
        .font(.caption2)
        .foregroundStyle(.secondary)
    }
  }

  /// Formats a date relative to the given reference. Exposed for testing.
  static func format(date: Date, relativeTo now: Date) -> String {
    let interval = now.timeIntervalSince(date)

    if interval < nowThreshold {
      return L10n.Chats.Chats.Timestamp.now
    }

    if interval >= weekThreshold {
      return date.formatted(.dateTime.month(.abbreviated).day())
    }

    return relativeFormatter.localizedString(for: date, relativeTo: now)
  }

  /// Formats a wire-format unix timestamp relative to the given date. Exposed for testing.
  static func format(timestamp: UInt32, relativeTo now: Date) -> String {
    format(date: Date(timeIntervalSince1970: TimeInterval(timestamp)), relativeTo: now)
  }
}

#Preview {
  VStack(alignment: .trailing, spacing: 8) {
    RelativeTimestampText(date: Date())
    RelativeTimestampText(date: Date().addingTimeInterval(-120))
    RelativeTimestampText(date: Date().addingTimeInterval(-3600))
    RelativeTimestampText(date: Date().addingTimeInterval(-86400))
    RelativeTimestampText(date: Date().addingTimeInterval(-259_200))
  }
  .padding()
}
